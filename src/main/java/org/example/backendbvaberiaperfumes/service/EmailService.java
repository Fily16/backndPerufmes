package org.example.backendbvaberiaperfumes.service;

import jakarta.mail.internet.MimeMessage;
import org.example.backendbvaberiaperfumes.model.Order;
import org.example.backendbvaberiaperfumes.model.OrderItem;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.OrderItemRepository;
import org.example.backendbvaberiaperfumes.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Envía por correo la notificación cuando un pedido se SEPARA (aceptación).
 * Configurar MAIL_USERNAME / MAIL_PASSWORD (contraseña de aplicación de Gmail).
 */
@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final OrderRepository orderRepo;
    private final OrderItemRepository orderItemRepo;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${spring.mail.password:}")
    private String mailPassword;

    @Value("${app.notify.from:}")
    private String from;

    @Value("${app.notify.emails:}")
    private String notifyEmails;

    public EmailService(JavaMailSender mailSender, OrderRepository orderRepo, OrderItemRepository orderItemRepo) {
        this.mailSender = mailSender;
        this.orderRepo = orderRepo;
        this.orderItemRepo = orderItemRepo;
    }

    /** Diagnóstico: intenta enviar un correo de prueba y devuelve el resultado o el error exacto. */
    public java.util.Map<String, Object> diagnose(String to) {
        java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("user", mailUsername);
        r.put("from", (from == null || from.isBlank()) ? mailUsername : from);
        r.put("passwordSet", mailPassword != null && !mailPassword.isBlank());
        r.put("recipients", notifyEmails);
        if (mailPassword == null || mailPassword.isBlank()) {
            r.put("result", "MAIL_PASSWORD vacío o no configurado");
            return r;
        }
        String dest = (to != null && !to.isBlank()) ? to.trim() : mailUsername;
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom((from == null || from.isBlank()) ? mailUsername : from);
            helper.setTo(dest);
            helper.setSubject("Prueba de correo · AromaStudio");
            helper.setText("<b>El envío de correos funciona.</b> Este es un mensaje de prueba.", true);
            mailSender.send(msg);
            r.put("result", "ENVIADO OK a " + dest);
        } catch (Exception e) {
            Throwable c = e; StringBuilder sb = new StringBuilder();
            while (c != null) { sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append(" | "); c = c.getCause(); }
            r.put("result", "ERROR -> " + sb);
        }
        return r;
    }

    @jakarta.annotation.PostConstruct
    void logConfig() {
        boolean passwordSet = mailPassword != null && !mailPassword.isBlank();
        System.out.println("[EmailService] Config correo -> user=" + mailUsername
                + " passwordSet=" + passwordSet + " from=" + from + " to=" + notifyEmails);
    }

    /** Dispara el correo en segundo plano para no bloquear la respuesta ni romper el flujo si falla. */
    public void sendNewOrderNotification(Long orderId) {
        new Thread(() -> {
            try { doSend(orderId); }
            catch (Exception e) { System.err.println("[EmailService] No se pudo enviar la notificación: " + e.getMessage()); }
        }, "order-mail-" + orderId).start();
    }

    private void doSend(Long orderId) {
        if (mailUsername == null || mailUsername.isBlank() || mailPassword == null || mailPassword.isBlank()) {
            System.out.println("[EmailService] MAIL_PASSWORD no configurado; se omite el correo del pedido.");
            return;
        }
        String[] recipients = Arrays.stream(notifyEmails.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        if (recipients.length == 0) return;

        Order order = orderRepo.findById(orderId).orElse(null);
        if (order == null) return;
        List<OrderItem> items = orderItemRepo.findByOrderId(orderId); // product es EAGER

        String subject = "Nuevo pedido · " + safe(order.getOrderCode()) + " · " + safe(order.getClientName());
        String html = buildHtml(order, items);

        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom((from == null || from.isBlank()) ? mailUsername : from);
            helper.setTo(recipients);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(msg);
            System.out.println("[EmailService] Notificación de nuevo pedido enviada para " + order.getOrderCode());
        } catch (Exception e) {
            System.err.println("[EmailService] Error enviando correo: " + e.getMessage());
        }
    }

    private String buildHtml(Order order, List<OrderItem> items) {
        StringBuilder rows = new StringBuilder();
        int totalUnits = 0;
        for (OrderItem it : items) {
            Product p = it.getProduct();
            int qty = it.getQuantity() != null ? it.getQuantity() : 0;
            totalUnits += qty;
            String img = p != null && p.getImageUrl() != null && !p.getImageUrl().isBlank()
                    ? "<img src=\"" + esc(p.getImageUrl()) + "\" alt=\"\" width=\"56\" height=\"56\" style=\"width:56px;height:56px;object-fit:contain;border-radius:8px;background:#f5f5f7\">"
                    : "<div style=\"width:56px;height:56px;border-radius:8px;background:#f0f0f3\"></div>";
            String name = p != null ? (esc(p.getBrand()) + " " + esc(p.getName()) + (p.getMl() != null ? " · " + p.getMl() + "ml" : "")) : "—";
            rows.append("<tr>")
                .append("<td style=\"padding:8px 6px;border-bottom:1px solid #eee\">").append(img).append("</td>")
                .append("<td style=\"padding:8px 6px;border-bottom:1px solid #eee;font-size:14px\">").append(name).append("</td>")
                .append("<td style=\"padding:8px 6px;border-bottom:1px solid #eee;text-align:center;font-weight:700\">x").append(qty).append("</td>")
                .append("<td style=\"padding:8px 6px;border-bottom:1px solid #eee;text-align:right\">S/ ").append(money(it.getUnitPricePen())).append("</td>")
                .append("<td style=\"padding:8px 6px;border-bottom:1px solid #eee;text-align:right;font-weight:700\">S/ ").append(money(it.getSubtotalPen())).append("</td>")
                .append("</tr>");
        }

        String delivery;
        if ("SHALOM".equalsIgnoreCase(order.getDeliveryMethod()) || "PROVINCIA".equalsIgnoreCase(order.getDeliveryMethod())) {
            String destino = (order.getShippingDepartment() != null || order.getShippingAgency() != null)
                    ? (safe(order.getShippingDepartment()) + " - " + safe(order.getShippingAgency()))
                    : safe(order.getShippingAddress());
            delivery = "<b>Provincia (Shalom)</b><br>" +
                    "DNI: " + safe(order.getShippingDni()) + "<br>" +
                    "Nombre: " + safe(order.getShippingName()) + "<br>" +
                    "Destino: " + esc(destino) + "<br>" +
                    "Teléfono: " + safe(order.getShippingPhone());
        } else {
            delivery = "<b>Lima</b> (coordinar entrega)";
        }

        String fecha = order.getUpdatedAt() != null
                ? order.getUpdatedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) : "";

        return "<div style=\"font-family:Arial,Helvetica,sans-serif;max-width:640px;margin:0 auto;color:#1a1a2e\">"
            + "<div style=\"background:#111;color:#fff;padding:18px 22px;border-radius:12px 12px 0 0\">"
            + "<h2 style=\"margin:0;font-size:18px\">Nuevo pedido registrado</h2>"
            + "<p style=\"margin:6px 0 0;opacity:.85;font-size:13px\">Pedido " + safe(order.getOrderCode()) + " · " + fecha + "</p>"
            + "</div>"
            + "<div style=\"border:1px solid #eee;border-top:none;border-radius:0 0 12px 12px;padding:20px 22px\">"
            + "<table style=\"width:100%;font-size:14px;margin-bottom:14px\">"
            + "<tr><td style=\"padding:3px 0;color:#777\">Realizado por (cliente)</td><td style=\"padding:3px 0;text-align:right;font-weight:600\">" + safe(order.getClientName()) + " · " + safe(order.getClientPhone()) + "</td></tr>"
            + "<tr><td style=\"padding:3px 0;color:#777\">Canal</td><td style=\"padding:3px 0;text-align:right;font-weight:600\">" + ("STOCK".equalsIgnoreCase(order.getChannel()) ? "Entrega inmediata (stock)" : "Consolidado (por encargo)") + "</td></tr>"
            + "<tr><td style=\"padding:3px 0;color:#777;vertical-align:top\">Entrega</td><td style=\"padding:3px 0;text-align:right\">" + delivery + "</td></tr>"
            + "</table>"
            + "<table style=\"width:100%;border-collapse:collapse\">"
            + "<thead><tr style=\"text-align:left;font-size:12px;color:#999\">"
            + "<th style=\"padding:6px\"></th><th style=\"padding:6px\">Perfume</th><th style=\"padding:6px;text-align:center\">Cant.</th><th style=\"padding:6px;text-align:right\">Precio</th><th style=\"padding:6px;text-align:right\">Subtotal</th>"
            + "</tr></thead><tbody>" + rows + "</tbody></table>"
            + "<table style=\"width:100%;font-size:15px;margin-top:14px\">"
            + "<tr><td style=\"padding:3px 0;color:#777\">Unidades</td><td style=\"padding:3px 0;text-align:right\">" + totalUnits + "</td></tr>"
            + "<tr><td style=\"padding:3px 0;color:#777\">Separación</td><td style=\"padding:3px 0;text-align:right\">S/ " + money(order.getDepositAmountPen()) + "</td></tr>"
            + "<tr><td style=\"padding:3px 0;color:#777\">Resta por pagar</td><td style=\"padding:3px 0;text-align:right\">S/ " + money(order.getRemainingPen()) + "</td></tr>"
            + "<tr><td style=\"padding:8px 0 0;font-weight:800;font-size:17px\">Total</td><td style=\"padding:8px 0 0;text-align:right;font-weight:800;font-size:17px\">S/ " + money(order.getTotalPen()) + "</td></tr>"
            + "</table>"
            + "<p style=\"margin-top:18px;font-size:12px;color:#aaa\">AromaStudio · notificación automática de separación</p>"
            + "</div></div>";
    }

    private static String money(Double v) {
        return String.format(Locale.US, "%.2f", v != null ? v : 0.0);
    }
    private static String safe(String s) { return s == null ? "" : esc(s); }
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
