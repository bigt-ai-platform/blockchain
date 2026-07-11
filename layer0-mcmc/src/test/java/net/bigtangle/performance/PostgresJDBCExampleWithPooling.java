package net.bigtangle.performance;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class PostgresJDBCExampleWithPooling {
    private static HikariDataSource ds;

    static {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/info"); //Replace as needed
        config.setUsername("root"); //Replace as needed
        config.setPassword("test1234"); //Replace as needed
        config.setDriverClassName("org.postgresql.Driver"); //explicitly set driver
        config.setMaximumPoolSize(10); // Limit number of connections
        config.setMinimumIdle(5);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        ds = new HikariDataSource(config);
    }

   public static void main(String[] args) {
        try(Connection connection = ds.getConnection()) {
            // Use the database connection
            String sql = "SELECT * FROM some_table";

           try(PreparedStatement preparedStatement = connection.prepareStatement(sql);
                ResultSet resultSet = preparedStatement.executeQuery()){
               while(resultSet.next()){
                  int id = resultSet.getInt("id");
                  String name = resultSet.getString("name");
                  System.out.println("ID: " + id + " , Name: " + name);
               }
           }

       } catch (SQLException e) {
           System.out.println("Error connecting to the DB" + e.getMessage());
           e.printStackTrace();
       }
    }
}
 