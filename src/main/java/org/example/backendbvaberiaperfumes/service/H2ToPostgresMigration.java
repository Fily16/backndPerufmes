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

    /**
     * Orden padre -> hijo para respetar llaves foráneas al insertar.
     * MANTENER ESTA LISTA AL DIA: cada entidad/tabla nueva que deba sobrevivir la migracion se agrega aqui
     * (si no, sus datos NO se copian). Hoy estan las 26 entidades de model/.
     * FK reales (JPA): supplier_offers/supplier_constraints -> suppliers (+ products), orders -> consolidados,
     * order_items/order_promos -> orders, promotion_items -> promotions, retail_* -> products,
     * purchase_plan_lines -> purchase_plans. El resto guarda ids sueltos (Long sin FK): el orden solo es prolijo.
     * Una tabla que aun no existe en el H2 de origen se omite al copiar (ver copyTable).
     */
    private static final String[] TABLES = {
            "admins", "app_config", "suppliers", "products", "consolidados", "promotions", "media_images",
            "supplier_offers", "supplier_constraints", "orders", "order_items", "order_promos", "promotion_items",
            "retail_inventory", "retail_sales",
            "import_batches", "match_candidates", "image_cache", "missing_resolution",
            "purchase_plans", "purchase_plan_lines",
            "nso_records", "product_nso", "nso_candidates", "nso_aliases", "nso_events"
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
        // Tablas con clave asignada (nso_records, product_nso) no tienen columna id: un MAX(id) fallido
        // abortaria TODA la transaccion de Postgres, asi que se saltan antes de ejecutar nada.
        if (!hasIdColumn(pg, table)) return;
        // IDENTITY -> secuencia serial de la columna; SEQUENCE de Hibernate (tablas NSO) -> "<tabla>_seq".
        // Si no existe ninguna, setval(NULL, ...) devuelve NULL sin error.
        String sql = "SELECT setval(COALESCE(pg_get_serial_sequence('" + table + "','id'), " +
                "CAST(to_regclass('" + table + "_seq') AS text)), " +
                "GREATEST((SELECT COALESCE(MAX(id),0) FROM " + table + "), 1))";
        try (Statement st = pg.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            System.out.println("[MIGRATION]   (secuencia " + table + " no reajustada: " + e.getMessage() + ")");
        }
    }

    private boolean hasIdColumn(Connection pg, String table) {
        try (ResultSet rs = pg.getMetaData().getColumns(null, null, table, "id")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }
}
