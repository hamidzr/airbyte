/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.source.mysql;

import static io.airbyte.integrations.base.errors.utils.ConnectionErrorType.INCORRECT_DB_NAME;
import static io.airbyte.integrations.base.errors.utils.ConnectionErrorType.INCORRECT_HOST_OR_PORT;
import static io.airbyte.integrations.base.errors.utils.ConnectionErrorType.INCORRECT_USERNAME_OR_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.mysql.cj.MysqlType;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.resources.MoreResources;
import io.airbyte.commons.string.Strings;
import io.airbyte.db.Database;
import io.airbyte.db.factory.DSLContextFactory;
import io.airbyte.db.factory.DatabaseDriver;
import io.airbyte.integrations.source.jdbc.AbstractJdbcSource;
import io.airbyte.integrations.source.jdbc.test.JdbcSourceAcceptanceTest;
import io.airbyte.protocol.models.AirbyteConnectionStatus;
import io.airbyte.protocol.models.ConnectorSpecification;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.Callable;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

class MySqlJdbcSourceAcceptanceTest extends JdbcSourceAcceptanceTest {

  protected static final String TEST_USER = "test";
  protected static final Callable<String> TEST_PASSWORD = () -> "test";
  protected static MySQLContainer<?> container;

  protected Database database;
  protected DSLContext dslContext;

  @BeforeAll
  static void init() throws Exception {
    container = new MySQLContainer<>("mysql:8.0")
        .withUsername(TEST_USER)
        .withPassword(TEST_PASSWORD.call())
        .withEnv("MYSQL_ROOT_HOST", "%")
        .withEnv("MYSQL_ROOT_PASSWORD", TEST_PASSWORD.call());
    container.start();
    final Connection connection = DriverManager.getConnection(container.getJdbcUrl(), "root", TEST_PASSWORD.call());
    connection.createStatement().execute("GRANT ALL PRIVILEGES ON *.* TO '" + TEST_USER + "'@'%';\n");
  }

  @BeforeEach
  public void setup() throws Exception {
    config = Jsons.jsonNode(ImmutableMap.builder()
        .put("host", container.getHost())
        .put("port", container.getFirstMappedPort())
        .put("database", Strings.addRandomSuffix("db", "_", 10))
        .put("username", TEST_USER)
        .put("password", TEST_PASSWORD.call())
        .build());

    dslContext = DSLContextFactory.create(
        config.get("username").asText(),
        config.get("password").asText(),
        DatabaseDriver.MYSQL.getDriverClassName(),
        String.format("jdbc:mysql://%s:%s",
            config.get("host").asText(),
            config.get("port").asText()),
        SQLDialect.MYSQL);
    database = new Database(dslContext);

    database.query(ctx -> {
      ctx.fetch("CREATE DATABASE " + config.get("database").asText());
      return null;
    });

    super.setup();
  }

  @AfterEach
  void tearDownMySql() throws Exception {
    dslContext.close();
    super.tearDown();
  }

  @AfterAll
  static void cleanUp() {
    container.close();
  }

  // MySql does not support schemas in the way most dbs do. Instead we namespace by db name.
  @Override
  public boolean supportsSchemas() {
    return false;
  }

  @Override
  public AbstractJdbcSource<MysqlType> getJdbcSource() {
    return new MySqlSource();
  }

  @Override
  public String getDriverClass() {
    return MySqlSource.DRIVER_CLASS;
  }

  @Override
  public JsonNode getConfig() {
    return Jsons.clone(config);
  }

  @Test
  void testSpec() throws Exception {
    final ConnectorSpecification actual = source.spec();
    final ConnectorSpecification expected = Jsons.deserialize(MoreResources.readResource("spec.json"), ConnectorSpecification.class);

    assertEquals(expected, actual);
  }

  @Test
  void testCheckIncorrectPasswordFailure() throws Exception {
    ((ObjectNode) config).put("password", "fake");
    final AirbyteConnectionStatus actual = source.check(config);
    assertEquals(AirbyteConnectionStatus.Status.FAILED, actual.getStatus());
    assertEquals(INCORRECT_USERNAME_OR_PASSWORD.getValue(), actual.getMessage());
  }

  @Test
  public void testCheckIncorrectUsernameFailure() throws Exception {
    ((ObjectNode) config).put("username", "fake");
    final AirbyteConnectionStatus actual = source.check(config);
    assertEquals(AirbyteConnectionStatus.Status.FAILED, actual.getStatus());
    assertEquals(INCORRECT_USERNAME_OR_PASSWORD.getValue(), actual.getMessage());
  }

  @Test
  public void testCheckIncorrectHostFailure() throws Exception {
    ((ObjectNode) config).put("host", "localhost2");
    final AirbyteConnectionStatus actual = source.check(config);
    assertEquals(AirbyteConnectionStatus.Status.FAILED, actual.getStatus());
    assertEquals(INCORRECT_HOST_OR_PORT.getValue(), actual.getMessage());
  }

  @Test
  public void testCheckIncorrectPortFailure() throws Exception {
    ((ObjectNode) config).put("port", "0000");
    final AirbyteConnectionStatus actual = source.check(config);
    assertEquals(AirbyteConnectionStatus.Status.FAILED, actual.getStatus());
    assertEquals(INCORRECT_HOST_OR_PORT.getValue(),  actual.getMessage());
  }

  @Test
  public void testCheckIncorrectDataBaseFailure() throws Exception {
    ((ObjectNode) config).put("database", "wrongdatabase");
    final AirbyteConnectionStatus actual = source.check(config);
    assertEquals(AirbyteConnectionStatus.Status.FAILED, actual.getStatus());
    assertEquals(INCORRECT_DB_NAME.getValue(), actual.getMessage());
  }

}
