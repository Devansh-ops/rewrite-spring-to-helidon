package io.github.devanshops.rewrite.helidon.it.transaction.supports;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** H2-backed SUPPORTS observations shared by both providers. */
@ApplicationScoped
public class SupportsJdbcOutcomeStore {
    @Inject
    @Named("contract")
    DataSource dataSource;

    public void reset() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists supports_outcome (" +
                              "ordinal integer primary key, scenario varchar(80), " +
                              "outcome varchar(200))");
            statement.execute("create table if not exists supports_effect (" +
                              "marker varchar(80) primary key)");
            statement.executeUpdate("delete from supports_outcome");
            statement.executeUpdate("delete from supports_effect");
        }
    }

    public void writeEffect(String marker) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into supports_effect (marker) values (?)")) {
            statement.setString(1, marker);
            statement.executeUpdate();
        }
    }

    public boolean hasEffect(String marker) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from supports_effect where marker = ?")) {
            statement.setString(1, marker);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
    }

    public void record(int ordinal, String scenario, String outcome) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into supports_outcome (ordinal, scenario, outcome) values (?, ?, ?)")) {
            statement.setInt(1, ordinal);
            statement.setString(2, scenario);
            statement.setString(3, outcome);
            statement.executeUpdate();
        }
    }

    public List<String> normalizedOutcomes() throws SQLException {
        List<String> outcomes = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select scenario, outcome from supports_outcome order by ordinal")) {
            while (rows.next()) {
                outcomes.add(rows.getString(1) + "=" + rows.getString(2));
            }
        }
        return outcomes;
    }
}
