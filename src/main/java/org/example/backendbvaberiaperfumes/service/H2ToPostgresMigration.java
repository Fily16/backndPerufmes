package org.example.backendbvaberiaperfumes.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Migración de UN SOLO USO: copia todos los datos de la base H2 local -> la base
 * destino (Postgres/Aiven), REEMPLAZANDO el contenido del destino.
 *
 * Apagada por defecto. Se activa solo con app.migrate.h2=true (env APP_MIGRATE_H2=true),
 * apuntando la base primaria del app al destino (DATABASE_URL de Aiven). No corre en
 * despliegues normales.
 */
@Component
@Order(1)
public class H2ToPostgresMigration implements CommandLineRunner {

    @Value("${app.migrate.h2:false}")
    private boolean enabled;

    @Value("${app.migrate.h2.url:jdbc:h2:file:./data/aromastudio;DB_CLOSE_ON_EXIT=FALSE}")
    private String h2Url;

    @Value("${app.migrate.h2.user:sa}")
    private String h2User;

    @Value("${app.migrate.h2.password:}")
    private String h2Password;

    private final DataSource target; // base primaria del app = destino (Aiven)

    public H2ToPostgresMigration(DataSource target) {
        this.target = target;
    }

    /** Orden padre -> hijo para respetar llaves foráneas al insertar. */
    private static final String[] TABLES = {
            "admins", "app_config", "suppliers", "products", "consolidados", "promotions",
            "supplier_offers", "orders", "order_items", "order_promos", "promotion_items",
            "retail_inventory", "retail_sales"
    };

    @Override
    public void run(String... args) {
        if (!enabled) return;

        System.out.println("========================================================");
        System.out.println("[MIGRATION] Iniciando copia H2 local -> destino (REEMPLAZO)");
        System.out.println("[MIGRATION] Origen H2: " + h2Url);
        System.out.println("========================================================");

        try (Connection h2 = DriverManager.getConnection(h2Url, h2User, h2Password);
             Connection pg = target.getConnection()) {

            pg.setAutoCommit(false);

            // 1) Vaciar el destino (FK-safe) y reiniciar identidades
            try (Statement st = pg.createStatement()) {
                st.execute("TRUNCATE TABLE " + String.join(", ", TABLES) + " RESTART IDENTITY CASCADE");
            }
            System.out.println("[MIGRATION] Destino vaciado.");

            // 2) Copiar tabla por tabla, preservando IDs
            int total = 0;
            for (String t : TABLES) {
                int n = copyTable(h2, pg, t);
                total += n;
                System.out.println("[MIGRATION]   " + t + ": " + n + " filas");
            }

            // 3) Reajustar las secuencias de identidad al MAX(id)
            for (String t : TABLES) resetSequence(pg, t);

            pg.commit();
            System.out.println("[MIGRATION] COMPLETADA. Filas copiadas: " + total);
            System.out.println("[MIGRATION] El destino quedó igual a tu H2 local.");
        } catch (Exception e) {
            System.err.println("[MIGRATION] ERROR — no se hizo commit: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Migración fallida", e);
        }
    }

    private int copyTable(Connection h2, Connection pg, String table) throws SQLException {
        try (Statement s = h2.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM " + table)) {

            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            List<String> names = new ArrayList<>();
            StringBuilder placeholders = new StringBuilder();
            for (int i = 1; i <= cols; i++) {
                names.add(md.getColumnName(i));
                placeholders.append(i == 1 ? "?" : ",?");
            }
            String insert = "INSERT INTO " + table + " (" + String.join(", ", names) + ") VALUES (" + placeholders + ")";

            int count = 0;
            try (PreparedStatement ps = pg.prepareStatement(insert)) {
                while (rs.next()) {
                    for (int i = 1; i <= cols; i++) {
                        Object v = rs.getObject(i);
                        if (v instanceof Clob clob) {
                            v = clob.getSubString(1, (int) clob.length());
                        }
                        ps.setObject(i, v);
                    }
                    ps.addBatch();
                    if (++count % 200 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            return count;
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("not found") || msg.contains("no encontrada") || msg.contains("does not exist")) {
                System.out.println("[MIGRATION]   " + table + ": no existe en H2, se omite.");
                return 0;
            }
            throw e;
        }
    }

    private void resetSequence(Connection pg, String table) {
        String sql = "SELECT setval(pg_get_serial_sequence('" + table + "','id'), " +
                "GREATEST((SELECT COALESCE(MAX(id),0) FROM " + table + "), 1))";
        try (Statement st = pg.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            System.out.println("[MIGRATION]   (secuencia " + table + " no reajustada: " + e.getMessage() + ")");
        }
    }
}
