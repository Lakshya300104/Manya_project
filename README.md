# Office Management

A Java desktop app for managing customers, inventory, orders, deliveries, and payments. Employees process orders and record payments. Admins review activity, order history, and employee login records.

## Prerequisites

- Windows or macOS with JDK 21 or newer to build the app.
- A running MySQL instance and a MySQL account with permission to create and update the app database and tables.
- MySQL Workbench is optional. The JDBC driver is included.

## Quick start

Set the MySQL host, port, username, and password in `mysql.local.properties` in the project folder. Keep real credentials out of version control.

```properties
url=jdbc:mysql://localhost:3306/
user=your_mysql_username
password=your_mysql_password
```

From the project folder, check the connection and build the app.

```sh
java --class-path mysql-connector-j-26.7.0.jar CheckMySql.java
java BuildApp.java
```

Open `OfficeManagement/OfficeManagement.exe` on Windows or `OfficeManagement.app` on macOS. The app creates the `office_mgt` database and its tables on first launch.
