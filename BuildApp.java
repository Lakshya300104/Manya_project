import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BuildApp {
    private static final String APP = "OfficeManagement";
    private static final String MAIN = "officemgtapp.Login";
    private static final List<String> DEPENDENCIES = List.of(
            "jcalendar-1.4.jar", "mysql-connector-j-26.7.0.jar", "rs2xml.jar");

    public static void main(String[] args) throws Exception {
        Path project = Path.of("").toRealPath();
        Path sources = project.resolve("src/officemgtapp");
        if (!Files.isRegularFile(sources.resolve("Login.java"))) {
            throw new IOException("Run java BuildApp.java from the project folder.");
        }
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        boolean windows = os.startsWith("windows");
        if (!windows && !os.startsWith("mac")) {
            throw new IOException("Build this application on Windows or macOS.");
        }

        String packageName = windows ? APP : APP + ".app";
        Path target = project.resolve(packageName);
        String launcherConfig = windows ? "app/OfficeManagement.cfg" : "Contents/app/OfficeManagement.cfg";
        validateExistingPackage(target, launcherConfig);

        Path stage = Files.createTempDirectory(project, ".office-mgt-build-");
        Path previous = stage.resolve("previous");
        boolean installed = false;
        try {
            Path classes = Files.createDirectory(stage.resolve("classes"));
            Path input = Files.createDirectory(stage.resolve("input"));
            Path output = Files.createDirectory(stage.resolve("output"));
            String classpath = String.join(java.io.File.pathSeparator, DEPENDENCIES);
            List<String> compile = new ArrayList<>(List.of("--release", "21", "-encoding", "UTF-8",
                    "-cp", classpath, "-d", classes.toString()));
            try (var files = Files.list(sources)) {
                files.filter(file -> file.toString().endsWith(".java")).sorted()
                        .forEach(file -> compile.add(file.toString()));
            }
            run(windows, "javac", compile);
            run(windows, "jar", List.of("--create", "--file", input.resolve(APP + ".jar").toString(),
                    "--main-class", MAIN, "-C", classes.toString(), "."));
            for (String dependency : DEPENDENCIES) {
                Files.copy(project.resolve(dependency), input.resolve(dependency));
            }

            String configPath = windows ? "$APPDIR/../mysql.local.properties" : "$APPDIR/../../../mysql.local.properties";
            run(windows, "jpackage", List.of("--type", "app-image", "--name", APP,
                    "--input", input.toString(), "--main-jar", APP + ".jar", "--main-class", MAIN,
                    "--dest", output.toString(), "--java-options", "-Doffice.mgt.config=" + configPath));
            Path built = output.resolve(packageName);
            if (!Files.isRegularFile(built.resolve(launcherConfig))) {
                throw new IOException("The build did not produce a complete application package.");
            }
            validateExistingPackage(target, launcherConfig);
            if (Files.exists(target)) {
                Path localConfig = target.resolve("mysql.local.properties");
                if (windows && Files.isRegularFile(localConfig)) {
                    Files.copy(localConfig, built.resolve(localConfig.getFileName()));
                }
                try {
                    Files.move(target, previous);
                } catch (IOException ex) {
                    if (windows) {
                        throw new IOException("Could not replace the Windows package. Close OfficeManagement and retry the build.", ex);
                    }
                    throw ex;
                }
            }
            try {
                Files.move(built, target);
                installed = true;
            } catch (IOException ex) {
                if (Files.exists(previous)) {
                    try {
                        Files.move(previous, target);
                    } catch (IOException rollback) {
                        ex.addSuppressed(rollback);
                        throw new IOException("The previous package is available at " + previous, ex);
                    }
                }
                throw ex;
            }
            System.out.println("Built " + (windows ? target.resolve(APP + ".exe") : target));
        } finally {
            if (installed || !Files.exists(previous)) {
                try {
                    cleanStage(project, stage);
                } catch (IOException ex) {
                    System.err.println("Could not remove temporary build files at " + stage + ": " + ex.getMessage());
                }
            }
        }
    }

    private static void validateExistingPackage(Path target, String launcherConfig) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(target.resolve(launcherConfig)))) {
            throw new IOException("Cannot replace " + target + " because it is not an Office Management package.");
        }
    }

    private static void run(boolean windows, String tool, List<String> arguments) throws IOException, InterruptedException {
        Path executable = Path.of(System.getProperty("java.home"), "bin", tool + (windows ? ".exe" : ""));
        if (!Files.isRegularFile(executable)) {
            throw new IOException("Run the build with a JDK 21 or newer that includes " + tool + ".");
        }
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(arguments);
        int status = new ProcessBuilder(command).inheritIO().start().waitFor();
        if (status != 0) {
            throw new IOException(tool + " failed with exit code " + status + ".");
        }
    }

    private static void cleanStage(Path project, Path stage) throws IOException {
        if (!stage.getParent().equals(project) || !stage.getFileName().toString().startsWith(".office-mgt-build-")
                || !Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unexpected build directory: " + stage);
        }
        Files.walkFileTree(stage, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isSymbolicLink()) {
                    makeWritable(file);
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path folder, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                makeWritable(folder);
                Files.delete(folder);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void makeWritable(Path path) throws IOException {
        var dos = Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null && dos.readAttributes().isReadOnly()) {
            dos.setReadOnly(false);
        }
    }
}
