package br.com.mercadotonico.desktop;

import br.com.mercadotonico.core.AppException;
import br.com.mercadotonico.core.BusinessRules;
import br.com.mercadotonico.core.PaymentAllocationService;
import br.com.mercadotonico.core.SupportLogger;
import br.com.mercadotonico.core.UserPermissions;
import br.com.mercadotonico.db.MigrationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.w3c.dom.Element;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.*;

public class DesktopApp {
    private static final NumberFormat BRL = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"));
    private static final DateTimeFormatter BR_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Color MARKET_GREEN = new Color(16, 93, 64);
    private static final Color MARKET_GREEN_2 = new Color(0, 143, 104);
    private static final Color MARKET_RED = new Color(185, 49, 49);
    private static final Color MARKET_ORANGE = new Color(215, 126, 26);
    private static final Color MARKET_BG = new Color(233, 238, 244);
    private static final Color PANEL_BG = new Color(252, 253, 255);
    private static final Color DARK_SURFACE = new Color(21, 30, 42);
    private static final Color DARK_SURFACE_2 = new Color(32, 45, 61);
    private static final String XML_INBOX_DIR = "data/xml_nfe_entrada";
    private static final String DB_URL_ENV = "MERCADO_DB_URL";
    private static final String DB_URL_CONFIG = "config/desktop.properties";
    private static final Font TITLE_FONT = new Font("Segoe UI", Font.BOLD, 30);
    private static final Font SECTION_FONT = new Font("Segoe UI", Font.BOLD, 18);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
    private Connection con;
    private User user;
    private JFrame frame;
    private DefaultTableModel cartModel;
    private JLabel totalLabel;
    private JComboBox<Item> clienteCombo;
    private JComboBox<Item> caixaCombo;
    private DesktopInventoryService inventoryService;
    private DesktopCashReportService cashReportService;
    private DesktopReturnService returnService;
    private DesktopFinanceService financeService;
    private DesktopReceiptService receiptService;
    private Runnable paymentFeedbackUpdater = () -> {};
    private final List<CartItem> cart = new ArrayList<>();

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                new DesktopApp().start();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, e.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void start() throws Exception {
        setupLookAndFeel();
        connect();
        initDb();
        if (!loginDialog()) {
            return;
        }
        buildFrame();
    }

    private void connect() throws Exception {
        File data = new File("data");
        data.mkdirs();
        xmlInboxDir().toFile().mkdirs();
        String jdbcUrl = resolveDesktopDbUrl();
        con = DriverManager.getConnection(jdbcUrl);
        if (jdbcUrl.startsWith("jdbc:sqlite:")) {
            exec("PRAGMA foreign_keys = ON");
            exec("PRAGMA busy_timeout = 5000");
        }
        inventoryService = new DesktopInventoryService(con);
        cashReportService = new DesktopCashReportService(con);
        returnService = new DesktopReturnService(con);
        financeService = new DesktopFinanceService(con);
        receiptService = new DesktopReceiptService(con);
    }

    private void initDb() throws Exception {
        new MigrationRunner().migrate(con);
        if (oneInt("select count(*) from usuarios") == 0) {
            update("""
                insert into usuarios (nome, login, senha_hash, role, pin_hash, ativo, desconto_maximo, autoriza_preco_zero, senha_temporaria)
                values (?, ?, ?, ?, ?, 1, ?, ?, 1)
                """, "Administrador Tonico", "admin", encoder.encode("admin123"), "ADMIN", encoder.encode("1234"), 30, 1);
            update("""
                insert into usuarios (nome, login, senha_hash, role, pin_hash, ativo, desconto_maximo, autoriza_preco_zero, senha_temporaria)
                values (?, ?, ?, ?, ?, 1, ?, ?, 1)
                """, "Gerente Loja", "gerente", encoder.encode("gerente123"), "GERENTE", encoder.encode("4321"), 15, 0);
            update("""
                insert into usuarios (nome, login, senha_hash, role, ativo, desconto_maximo, autoriza_preco_zero, senha_temporaria)
                values (?, ?, ?, ?, 1, ?, ?, 1)
                """, "Operador Caixa 1", "caixa1", encoder.encode("caixa123"), "CAIXA", 5, 0);
            update("""
                insert into usuarios (nome, login, senha_hash, role, ativo, desconto_maximo, autoriza_preco_zero, senha_temporaria)
                values (?, ?, ?, ?, 1, ?, ?, 1)
                """, "Operador Caixa 2", "caixa2", encoder.encode("caixa123"), "CAIXA", 5, 0);
            update("""
                insert into usuarios (nome, login, senha_hash, role, ativo, desconto_maximo, autoriza_preco_zero, senha_temporaria)
                values (?, ?, ?, ?, 1, ?, ?, 1)
                """, "Operador Estoque", "estoque1", encoder.encode("estoque123"), "ESTOQUE", 0, 0);
        }
    }

    private boolean loginDialog() throws Exception {
        JTextField login = new JTextField("admin");
        JPasswordField senha = new JPasswordField("admin123");
        JPanel panel = formPanel();
        panel.add(label("Login")); panel.add(login);
        panel.add(label("Senha")); panel.add(senha);
        int option = JOptionPane.showConfirmDialog(null, panel, "Mercado do Tonico - Entrar", JOptionPane.OK_CANCEL_OPTION);
        if (option != JOptionPane.OK_OPTION) {
            return false;
        }
        try (PreparedStatement ps = con.prepareStatement("select * from usuarios where login=? and ativo=1")) {
            ps.setString(1, login.getText().trim());
            ResultSet rs = ps.executeQuery();
            if (rs.next() && encoder.matches(new String(senha.getPassword()), rs.getString("senha_hash"))) {
                boolean senhaTemporaria = rs.getInt("senha_temporaria") == 1;
                if (senhaTemporaria && !forcePasswordChange(rs.getLong("id"), rs.getString("nome"))) {
                    JOptionPane.showMessageDialog(null, "Troca de senha cancelada. Faca login novamente.");
                    return loginDialog();
                }
                user = new User(
                        rs.getLong("id"),
                        rs.getString("nome"),
                        rs.getString("login"),
                        rs.getString("role"),
                        money(rs.getObject("desconto_maximo") == null ? "0" : rs.getObject("desconto_maximo").toString()),
                        rs.getInt("autoriza_preco_zero") == 1
                );
                audit("LOGIN", "Entrada no aplicativo desktop");
                return true;
            }
        }
        JOptionPane.showMessageDialog(null, "Login ou senha invalidos.");
        return loginDialog();
    }

    private boolean forcePasswordChange(long usuarioId, String nomeUsuario) throws Exception {
        JPasswordField nova = new JPasswordField();
        JPasswordField confirmar = new JPasswordField();
        JPanel panel = formPanel();
        panel.add(label("Usuario")); panel.add(new JLabel(nomeUsuario));
        panel.add(label("Nova senha")); panel.add(nova);
        panel.add(label("Confirmar senha")); panel.add(confirmar);
        int option = JOptionPane.showConfirmDialog(
                null,
                panel,
                "Troca obrigatoria de senha",
                JOptionPane.OK_CANCEL_OPTION
        );
        if (option != JOptionPane.OK_OPTION) {
            return false;
        }
        String novaSenha = new String(nova.getPassword()).trim();
        String confirmarSenha = new String(confirmar.getPassword()).trim();
        if (novaSenha.length() < 6) {
            JOptionPane.showMessageDialog(null, "A senha precisa ter no minimo 6 caracteres.");
            return forcePasswordChange(usuarioId, nomeUsuario);
        }
        if (!novaSenha.equals(confirmarSenha)) {
            JOptionPane.showMessageDialog(null, "As senhas nao conferem.");
            return forcePasswordChange(usuarioId, nomeUsuario);
        }
        update("update usuarios set senha_hash=?, senha_temporaria=0 where id=?", encoder.encode(novaSenha), usuarioId);
        audit("PASSWORD_CHANGE", "Usuario trocou senha temporaria");
        JOptionPane.showMessageDialog(null, "Senha atualizada com sucesso.");
        return true;
    }

    private void buildFrame() {
        frame = new JFrame("Mercado do Tonico - Sistema Desktop");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(scale(1120), scale(680)));
        if (screenSize.width <= 1366 || screenSize.height <= 768) {
            frame.setSize(new Dimension((int) (screenSize.width * 0.96), (int) (screenSize.height * 0.92)));
            frame.setExtendedState(JFrame.NORMAL);
        } else {
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        }
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        tabs.setBackground(MARKET_BG);
        tabs.addTab("Painel", dashboardPanel());
        if (UserPermissions.canAccessPdv(user.role)) {
            tabs.addTab("PDV - Caixa", posPanel());
        }
        if (UserPermissions.canAccessInventory(user.role)) {
            tabs.addTab("Estoque", estoquePanel());
        }
        if (UserPermissions.canManageSuppliers(user.role)) {
            tabs.addTab("Fornecedores", fornecedoresPanel());
        }
        if (UserPermissions.canImportXml(user.role)) {
            tabs.addTab("XML NF-e", xmlPanel());
        }
        if (UserPermissions.canAccessFiado(user.role)) {
            tabs.addTab("Fiado", fiadoPanel());
        }
        if (UserPermissions.canAccessFinance(user.role)) {
            tabs.addTab("Financeiro", financeiroPanel());
        }
        if (UserPermissions.canAccessReports(user.role)) {
            tabs.addTab("Relatorios", relatoriosPanel());
        }
        frame.setJMenuBar(menuBar());
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(MARKET_BG);
        root.add(appHeader(), BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu menu = new JMenu("Mercado do Tonico");
        JMenuItem usuario = new JMenuItem("Usuario: " + user.nome + " (" + user.role + ")");
        usuario.setEnabled(false);
        JMenuItem sair = new JMenuItem("Sair");
        sair.addActionListener(e -> {
            frame.dispose();
            try { start(); } catch (Exception ex) { error(ex); }
        });
        menu.add(usuario);
        menu.add(sair);
        bar.add(menu);
        return bar;
    }

    private JPanel dashboardPanel() {
        JPanel panel = page();
        panel.add(title("Painel de operacao"));
        if (!UserPermissions.canAccessReports(user.role)) {
            panel.add(roleDashboard());
            return panel;
        }
        JPanel metrics = new JPanel(new GridLayout(1, 4, 12, 12));
        metrics.setOpaque(false);
        metrics.add(metricCard("Produtos cadastrados", String.valueOf(safeInt("select count(*) from produtos where ativo=1")), MARKET_GREEN_2));
        metrics.add(metricCard("Estoque baixo", String.valueOf(safeInt("select count(*) from produtos where ativo=1 and estoque_atual <= estoque_minimo")), MARKET_RED));
        metrics.add(metricCard("Vencendo em 30 dias", String.valueOf(safeInt("select count(*) from produtos where ativo=1 and validade is not null and date(validade) <= date('now','+30 day')")), MARKET_ORANGE));
        metrics.add(metricCard("Fiado aberto", moneyText(safeMoney("select coalesce(sum(valor-valor_pago),0) from fiado where status='ABERTO'")), MARKET_GREEN));
        panel.add(metrics);
        panel.add(Box.createVerticalStrut(14));
        JPanel grid = new JPanel(new GridLayout(2, 2, 14, 14));
        grid.setOpaque(false);
        grid.add(section("Status dos caixas", table("select c.numero as Caixa, c.status as Status, coalesce(u.nome, '-') as Operador from caixas c left join usuarios u on u.id=c.operador_atual_id order by c.numero")));
        grid.add(section("Produtos abaixo do minimo", table("select nome as Produto, estoque_atual as Atual, estoque_minimo as Minimo from produtos where ativo=1 and estoque_atual <= estoque_minimo order by estoque_atual")));
        grid.add(section("Produtos vencendo em 30 dias", table("select nome as Produto, validade as Validade from produtos where ativo=1 and validade is not null and date(validade) <= date('now','+30 day') order by validade")));
        grid.add(section("Fiado em aberto", table("select c.nome as Cliente, sum(f.valor-f.valor_pago) as Aberto from fiado f join clientes c on c.id=f.cliente_id where f.status='ABERTO' group by c.id order by Aberto desc")));
        panel.add(grid);
        return panel;
    }

    private JPanel roleDashboard() {
        JPanel wrapper = new JPanel();
        wrapper.setOpaque(false);
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        JPanel metrics = new JPanel(new GridLayout(1, 3, 12, 12));
        metrics.setOpaque(false);
        metrics.add(metricCard("Perfil", user.role, MARKET_GREEN));
        metrics.add(metricCard("Caixas disponiveis", String.valueOf(safeInt("select count(*) from caixas where status='FECHADO'")), MARKET_GREEN_2));
        metrics.add(metricCard("Horario", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), MARKET_ORANGE));
        wrapper.add(metrics);
        wrapper.add(Box.createVerticalStrut(12));
        if (UserPermissions.canAccessPdv(user.role)) {
            wrapper.add(section("Status dos caixas", table("select c.numero as Caixa, c.status as Status, coalesce(u.nome, '-') as Operador from caixas c left join usuarios u on u.id=c.operador_atual_id order by c.numero")));
        } else if (UserPermissions.canAccessInventory(user.role)) {
            wrapper.add(section("Reposicao prioritaria", table("select nome as Produto, estoque_atual as Atual, estoque_minimo as Minimo, validade as Validade from produtos where ativo=1 and (estoque_atual <= estoque_minimo or (validade is not null and date(validade) <= date('now','+30 day'))) order by estoque_atual, validade limit 20")));
        }
        return wrapper;
    }

    private JPanel posPanel() {
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setBackground(MARKET_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(scale(14), scale(14), scale(14), scale(14)));
        JPanel top = new JPanel(new GridLayout(1, 5, 10, 10));
        top.setBackground(PANEL_BG);
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 210, 224)),
                new EmptyBorder(12, 12, 12, 12)));
        caixaCombo = combo("select id, 'Caixa ' || numero || ' - ' || status from caixas order by numero");
        JTextField fundo = new JTextField("100.00");
        stylePdvField(fundo);
        JButton abrir = button("Abrir caixa");
        abrir.addActionListener(e -> openCaixa(fundo.getText()));
        top.add(label("Caixa")); top.add(caixaCombo);
        top.add(label("Fundo")); top.add(fundo);
        top.add(abrir);
        panel.add(top, BorderLayout.NORTH);

        cartModel = new DefaultTableModel(new Object[]{"ID", "Produto", "Qtd", "Preco", "Total"}, 0);
        JTable cartTable = new JTable(cartModel);
        styleTable(cartTable);
        JScrollPane cartScroll = new JScrollPane(cartTable);
        cartScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(195, 206, 220)),
                "Itens da venda",
                0,
                0,
                new Font("Segoe UI", Font.BOLD, 15),
                new Color(53, 71, 90)
        ));
        int sideWidth = Math.max(scale(320), Math.min(scale(470), (int) (screenSize.width * 0.30)));

        JPanel right = new JPanel();
        right.setPreferredSize(new Dimension(sideWidth, 0));
        right.setBackground(PANEL_BG);
        right.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(214, 221, 229)),
                new EmptyBorder(14, 14, 14, 14)));
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        JTextField codigo = new JTextField();
        codigo.setFont(new Font("Segoe UI", Font.BOLD, 23));
        codigo.setBackground(new Color(244, 252, 247));
        codigo.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(MARKET_GREEN_2, 2),
                new EmptyBorder(10, 10, 10, 10)));
        JTextField qtd = new JTextField("1");
        stylePdvField(qtd);
        JButton add = button("Adicionar produto");
        add.addActionListener(e -> {
            addCart(codigo.getText(), qtd.getText());
            codigo.setText("");
            qtd.setText("1");
            codigo.requestFocus();
        });
        totalLabel = new JLabel("R$ 0,00");
        totalLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        totalLabel.setOpaque(true);
        totalLabel.setBackground(new Color(230, 248, 239));
        totalLabel.setBorder(new EmptyBorder(12, 12, 12, 12));
        totalLabel.setFont(new Font("Segoe UI", Font.BOLD, 41));
        totalLabel.setForeground(new Color(0, 118, 60));
        clienteCombo = combo("select id, nome from clientes where bloqueado = 0 order by nome");
        clienteCombo.setSelectedIndex(-1);
        JTextField dinheiro = new JTextField("0");
        JTextField debito = new JTextField("0");
        JTextField credito = new JTextField("0");
        JTextField pix = new JTextField("0");
        JTextField fiado = new JTextField("0");
        JTextField creditoTroca = new JTextField("0");
        JTextField codigoValeTroca = new JTextField();
        JTextField desconto = new JTextField("0");
        JTextField pin = new JPasswordField();
        stylePdvField(dinheiro);
        stylePdvField(debito);
        stylePdvField(credito);
        stylePdvField(pix);
        stylePdvField(fiado);
        stylePdvField(creditoTroca);
        stylePdvField(codigoValeTroca);
        stylePdvField(desconto);
        stylePdvField(pin);
        JLabel paymentStatus = new JLabel("Faltante: R$ 0,00");
        paymentStatus.setFont(new Font("Segoe UI", Font.BOLD, 16));
        paymentStatus.setForeground(MARKET_GREEN_2);
        JButton finalizar = button("Finalizar venda");
        finalizar.addActionListener(e -> finalizarVenda(
                paymentInputs(dinheiro, debito, credito, pix, fiado, creditoTroca),
                codigoValeTroca.getText(),
                desconto.getText(),
                pin.getText()
        ));
        JButton limpar = button("Limpar carrinho");
        limpar.addActionListener(e -> { cart.clear(); refreshCart(); });
        JButton sangria = button("Sangria");
        sangria.addActionListener(e -> caixaOperacao("SANGRIA"));
        JButton suprimento = button("Suprimento");
        suprimento.addActionListener(e -> caixaOperacao("SUPRIMENTO"));
        JButton trocaDevolucao = button("Troca / devolucao");
        trocaDevolucao.addActionListener(e -> processarTrocaOuDevolucao());
        JButton comprovante = button("Reemitir comprovante");
        comprovante.addActionListener(e -> reemitirComprovante());
        JButton cancelar = button("Cancelar ultima venda");
        cancelar.addActionListener(e -> cancelarUltima());
        JButton fechar = button("Fechar caixa");
        fechar.addActionListener(e -> fecharCaixa());

        right.add(sectionTitle("Leitura de produto"));
        right.add(label("Codigo de barras, SKU ou nome")); right.add(codigo);
        right.add(label("Quantidade")); right.add(qtd);
        right.add(add); right.add(Box.createVerticalStrut(14));
        right.add(sectionTitle("Total da venda"));
        right.add(totalLabel);
        right.add(Box.createVerticalStrut(12));
        right.add(sectionTitle("Pagamento"));
        right.add(label("Dinheiro")); right.add(dinheiro);
        right.add(label("Debito")); right.add(debito);
        right.add(label("Credito")); right.add(credito);
        right.add(label("PIX")); right.add(pix);
        right.add(label("Fiado")); right.add(fiado);
        right.add(label("Credito troca")); right.add(creditoTroca);
        right.add(label("Codigo vale troca")); right.add(codigoValeTroca);
        right.add(label("Cliente para fiado / vale")); right.add(clienteCombo);
        right.add(label("Desconto")); right.add(desconto);
        right.add(label("Conferencia pagamento")); right.add(paymentStatus);
        right.add(label("PIN gerente")); right.add(pin);
        right.add(finalizar);
        right.add(Box.createVerticalStrut(8));
        JPanel actions = new JPanel(new GridLayout(4, 2, 8, 8));
        actions.setOpaque(false);
        actions.add(limpar);
        actions.add(suprimento);
        actions.add(sangria);
        actions.add(trocaDevolucao);
        actions.add(comprovante);
        actions.add(cancelar);
        actions.add(fechar);
        right.add(actions);
        right.add(Box.createVerticalStrut(10));
        JPanel shortcuts = new JPanel(new GridLayout(2, 4, 8, 8));
        shortcuts.setOpaque(false);
        shortcuts.add(shortcutChip("F1", "Codigo"));
        shortcuts.add(shortcutChip("F4", "Finalizar"));
        shortcuts.add(shortcutChip("F6", "Limpar"));
        shortcuts.add(shortcutChip("F7", "Suprimento"));
        shortcuts.add(shortcutChip("F8", "Sangria"));
        shortcuts.add(shortcutChip("F9", "Troca"));
        shortcuts.add(shortcutChip("F10", "Comprov."));
        shortcuts.add(shortcutChip("ESC", "Limpar cod."));
        right.add(sectionTitle("Atalhos rapidos"));
        right.add(shortcuts);
        paymentFeedbackUpdater = bindPaymentFeedback(paymentStatus, desconto, dinheiro, debito, credito, pix, fiado, creditoTroca);
        codigo.addActionListener(e -> add.doClick());
        qtd.addActionListener(e -> add.doClick());
        desconto.addActionListener(e -> finalizar.doClick());
        bindPdvShortcuts(panel, codigo, limpar, finalizar, suprimento, sangria, trocaDevolucao, comprovante);
        JScrollPane rightScroll = new JScrollPane(right);
        rightScroll.setBorder(BorderFactory.createEmptyBorder());
        rightScroll.getVerticalScrollBar().setUnitIncrement(18);
        rightScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, cartScroll, rightScroll);
        split.setResizeWeight(screenSize.width <= 1366 ? 0.62 : 0.68);
        split.setDividerLocation(screenSize.width <= 1366 ? 0.60 : 0.67);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);
        panel.add(split, BorderLayout.CENTER);
        SwingUtilities.invokeLater(codigo::requestFocus);
        return panel;
    }

    private JPanel estoquePanel() {
        JPanel panel = page();
        JPanel cadastroForm = new JPanel(new GridLayout(0, 4, 8, 8));
        cadastroForm.setOpaque(false);
        JTextField nome = new JTextField();
        JTextField barras = new JTextField();
        JTextField sku = new JTextField();
        JTextField categoria = new JTextField("Mercearia");
        JTextField unidade = new JTextField("un");
        JTextField custo = new JTextField("0");
        JTextField venda = new JTextField("0");
        JTextField estoque = new JTextField("0");
        JTextField minimo = new JTextField("0");
        JTextField local = new JTextField();
        JTextField validade = new JTextField(LocalDate.now().plusMonths(6).toString());
        JTextField observacoes = new JTextField();
        cadastroForm.add(label("Nome")); cadastroForm.add(nome);
        cadastroForm.add(label("Codigo barras")); cadastroForm.add(barras);
        cadastroForm.add(label("SKU")); cadastroForm.add(sku);
        cadastroForm.add(label("Categoria")); cadastroForm.add(categoria);
        cadastroForm.add(label("Unidade")); cadastroForm.add(unidade);
        cadastroForm.add(label("Custo")); cadastroForm.add(custo);
        cadastroForm.add(label("Venda")); cadastroForm.add(venda);
        cadastroForm.add(label("Estoque inicial")); cadastroForm.add(estoque);
        cadastroForm.add(label("Estoque minimo")); cadastroForm.add(minimo);
        cadastroForm.add(label("Prateleira")); cadastroForm.add(local);
        cadastroForm.add(label("Validade AAAA-MM-DD")); cadastroForm.add(validade);
        cadastroForm.add(label("Observacoes")); cadastroForm.add(observacoes);
        JButton salvar = button("Salvar produto");
        salvar.addActionListener(e -> {
            try {
                requireInventoryAccess();
                long produtoId = inventoryService.saveProduct(
                        new DesktopInventoryService.ProductDraft(
                                nome.getText(),
                                barras.getText(),
                                sku.getText(),
                                categoria.getText(),
                                unidade.getText(),
                                money(custo.getText()),
                                money(venda.getText()),
                                money(estoque.getText()),
                                money(minimo.getText()),
                                local.getText(),
                                validade.getText(),
                                observacoes.getText()
                        ),
                        user.id,
                        nextInternalCode()
                );
                audit("SALVAR_PRODUTO", nome.getText());
                appLog("INFO", "estoque", "Produto cadastrado", "produtoId=" + produtoId);
                refreshFrame();
            } catch (Exception ex) { error(ex); }
        });

        JPanel entradaForm = new JPanel(new GridLayout(0, 4, 8, 8));
        entradaForm.setOpaque(false);
        JTextField entradaProdutoId = new JTextField();
        JTextField entradaQuantidade = new JTextField();
        JTextField entradaCusto = new JTextField();
        JTextField entradaLote = new JTextField();
        JTextField entradaValidade = new JTextField();
        JTextField entradaDocumento = new JTextField();
        JTextField entradaObservacao = new JTextField("Entrada manual de mercadoria");
        entradaForm.add(label("Produto ID")); entradaForm.add(entradaProdutoId);
        entradaForm.add(label("Quantidade")); entradaForm.add(entradaQuantidade);
        entradaForm.add(label("Custo unitario")); entradaForm.add(entradaCusto);
        entradaForm.add(label("Lote")); entradaForm.add(entradaLote);
        entradaForm.add(label("Validade AAAA-MM-DD")); entradaForm.add(entradaValidade);
        entradaForm.add(label("Documento")); entradaForm.add(entradaDocumento);
        entradaForm.add(label("Observacao")); entradaForm.add(entradaObservacao);
        JButton registrarEntrada = button("Registrar entrada");
        registrarEntrada.addActionListener(e -> {
            try {
                requireInventoryAccess();
                inventoryService.registerStockEntry(
                        new DesktopInventoryService.StockEntryRequest(
                                Long.parseLong(entradaProdutoId.getText().trim()),
                                money(entradaQuantidade.getText()),
                                money(entradaCusto.getText()),
                                entradaLote.getText(),
                                entradaValidade.getText(),
                                entradaDocumento.getText(),
                                entradaObservacao.getText()
                        ),
                        user.id
                );
                audit("ENTRADA_MANUAL", "Produto " + entradaProdutoId.getText());
                refreshFrame();
            } catch (Exception ex) { error(ex); }
        });

        JButton ajuste = button("Ajustar estoque");
        ajuste.addActionListener(e -> ajustarEstoque());
        JButton inventario = button("Fechar contagem de inventario");
        inventario.addActionListener(e -> reconciliarInventario());
        JButton perdas = button("Registrar perda/quebra");
        perdas.addActionListener(e -> registrarPerdaOuQuebra());

        JPanel formsGrid = new JPanel(new GridLayout(2, 1, 12, 12));
        formsGrid.setOpaque(false);
        formsGrid.add(section("Cadastro de produto", formWithAction(cadastroForm, salvar)));
        formsGrid.add(section("Entrada manual de mercadoria", formWithAction(entradaForm, registrarEntrada)));

        JPanel actions = new JPanel(new GridLayout(1, 3, 8, 8));
        actions.setOpaque(false);
        actions.add(ajuste);
        actions.add(inventario);
        actions.add(perdas);

        JPanel tablesGrid = new JPanel(new GridLayout(0, 2, 12, 12));
        tablesGrid.setOpaque(false);
        tablesGrid.add(section("Produtos", table("""
            select id as ID, codigo_interno as Interno, nome as Produto, codigo_barras as Barras, sku as SKU,
                   categoria as Categoria, unidade as Unidade, estoque_atual as Estoque, estoque_minimo as Minimo,
                   preco_custo as Custo, preco_venda as Venda, validade as Validade
            from produtos order by nome
            """)));
        tablesGrid.add(section("Movimentacoes recentes", table("""
            select m.id as ID, m.produto_id as ProdutoID, p.nome as Produto, m.tipo as Tipo, m.quantidade as Quantidade,
                   m.timestamp as DataHora, m.observacao as Observacao
            from movimentacao_estoque m join produtos p on p.id = m.produto_id
            order by m.id desc limit 40
            """)));
        tablesGrid.add(section("Historico de preco", table("""
            select h.id as ID, h.produto_id as ProdutoID, p.nome as Produto, h.preco_custo_anterior as CustoAnterior,
                   h.preco_custo_novo as CustoNovo, h.preco_venda_anterior as VendaAnterior, h.preco_venda_novo as VendaNova,
                   h.timestamp as DataHora, h.motivo as Motivo
            from historico_preco h join produtos p on p.id = h.produto_id
            order by h.id desc limit 40
            """)));
        tablesGrid.add(section("Lotes e validades", table("""
            select e.id as Entrada, e.produto_id as ProdutoID, p.nome as Produto, coalesce(e.lote,'-') as Lote,
                   coalesce(e.validade,'-') as Validade, e.quantidade as Quantidade, e.documento as Documento, e.criado_em as EntradaEm
            from entradas_estoque e join produtos p on p.id = e.produto_id
            where e.lote is not null or e.validade is not null
            order by case when e.validade is null then 1 else 0 end, date(e.validade), e.id desc
            limit 40
            """)));

        panel.add(formsGrid);
        panel.add(Box.createVerticalStrut(12));
        panel.add(actions);
        panel.add(Box.createVerticalStrut(12));
        panel.add(tablesGrid);
        return panel;
    }

    private JPanel fornecedoresPanel() {
        JPanel panel = page();
        JPanel form = new JPanel(new GridLayout(0, 4, 8, 8));
        JTextField razao = new JTextField();
        JTextField fantasia = new JTextField();
        JTextField cnpj = new JTextField();
        JTextField telefone = new JTextField();
        JTextField email = new JTextField();
        JTextField endereco = new JTextField();
        JTextField contato = new JTextField();
        form.add(label("Razao social")); form.add(razao);
        form.add(label("Fantasia")); form.add(fantasia);
        form.add(label("CNPJ")); form.add(cnpj);
        form.add(label("Telefone")); form.add(telefone);
        form.add(label("Email")); form.add(email);
        form.add(label("Endereco")); form.add(endereco);
        form.add(label("Contato")); form.add(contato);
        JButton salvar = button("Salvar fornecedor");
        salvar.addActionListener(e -> {
            try {
                requireInventoryAccess();
                BusinessRules.requireNotBlank(razao.getText(), "Razao social");
                update("""
                    insert into fornecedores (razao_social, nome_fantasia, cnpj, telefone, email, endereco, contato)
                    values (?, ?, ?, ?, ?, ?, ?)
                    on conflict(cnpj) do update set razao_social=excluded.razao_social, nome_fantasia=excluded.nome_fantasia, telefone=excluded.telefone, email=excluded.email, endereco=excluded.endereco, contato=excluded.contato
                    """, razao.getText(), fantasia.getText(), cnpj.getText(), telefone.getText(), email.getText(), endereco.getText(), contato.getText());
                refreshFrame();
            } catch (Exception ex) { error(ex); }
        });
        panel.add(section("Fornecedor", form));
        panel.add(salvar);
        panel.add(new JScrollPane(table("select razao_social as Razao, nome_fantasia as Fantasia, cnpj as CNPJ, telefone as Telefone, email as Email from fornecedores order by razao_social")));
        return panel;
    }

    private JPanel xmlPanel() {
        JPanel panel = page();
        Path inbox = xmlInboxDir();
        JLabel pastaInfo = new JLabel("Pasta padrao de XML: " + inbox.toAbsolutePath());
        pastaInfo.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        pastaInfo.setForeground(new Color(64, 79, 95));
        JButton abrirPasta = button("Abrir pasta de XML");
        abrirPasta.addActionListener(e -> abrirPastaXml());
        JButton importarMaisRecente = button("Importar ultimo XML da pasta");
        importarMaisRecente.addActionListener(e -> importarUltimoXmlDaPasta());
        JButton importar = button("Escolher XML NF-e e importar");
        importar.addActionListener(e -> importarXml());
        panel.add(pastaInfo);
        panel.add(abrirPasta);
        panel.add(importarMaisRecente);
        panel.add(importar);
        panel.add(section("Notas importadas", table("select numero_nf as NF, data as Data, total as Total, importado_em as Importado from notas_fiscais order by id desc")));
        return panel;
    }

    private JPanel fiadoPanel() {
        JPanel panel = page();
        JPanel form = new JPanel(new GridLayout(0, 4, 8, 8));
        JTextField nome = new JTextField();
        JTextField cpf = new JTextField();
        JTextField telefone = new JTextField();
        JTextField endereco = new JTextField();
        JTextField limite = new JTextField("0");
        form.add(label("Nome")); form.add(nome);
        form.add(label("CPF")); form.add(cpf);
        form.add(label("Telefone")); form.add(telefone);
        form.add(label("Endereco")); form.add(endereco);
        form.add(label("Limite")); form.add(limite);
        JButton salvar = button("Salvar cliente");
        salvar.addActionListener(e -> {
            try {
                requireFiadoAccess();
                BusinessRules.requireNotBlank(nome.getText(), "Nome do cliente");
                BigDecimal limiteCredito = money(limite.getText());
                BusinessRules.requireNonNegative(limiteCredito, "Limite de credito");
                update("""
                    insert into clientes (nome, cpf, telefone, endereco, limite_credito, bloqueado)
                    values (?, ?, ?, ?, ?, 0)
                    on conflict(cpf) do update set nome=excluded.nome, telefone=excluded.telefone, endereco=excluded.endereco, limite_credito=excluded.limite_credito
                    """, nome.getText(), cpf.getText(), telefone.getText(), endereco.getText(), limiteCredito);
                refreshFrame();
            } catch (Exception ex) { error(ex); }
        });
        JButton pagar = button("Registrar pagamento de fiado");
        pagar.addActionListener(e -> pagarFiado());
        panel.add(section("Cliente", form));
        panel.add(salvar);
        panel.add(section("Clientes", table("""
            select c.id as ID, c.nome as Nome, c.cpf as CPF, c.limite_credito as Limite, coalesce(sum(f.valor - f.valor_pago),0) as Divida
            from clientes c left join fiado f on f.cliente_id=c.id and f.status='ABERTO'
            group by c.id order by c.nome
            """)));
        panel.add(pagar);
        panel.add(section("Fiado em aberto", table("select f.id as ID, c.nome as Cliente, f.valor as Valor, f.valor_pago as Pago, f.data_criacao as Data from fiado f join clientes c on c.id=f.cliente_id where f.status='ABERTO' order by f.id desc")));
        return panel;
    }

    private JPanel financeiroPanel() {
        JPanel panel = page();
        JPanel resumo = new JPanel(new GridLayout(1, 4, 12, 12));
        resumo.setOpaque(false);
        Map<String, BigDecimal> dueSummary = financeDueSummary();
        resumo.add(metricCard("Contas a pagar", moneyText(dueSummary.getOrDefault("pagar_aberto", BigDecimal.ZERO)), MARKET_RED));
        resumo.add(metricCard("Contas a receber", moneyText(dueSummary.getOrDefault("receber_aberto", BigDecimal.ZERO)), MARKET_GREEN_2));
        resumo.add(metricCard("Vencendo hoje", moneyText(dueSummary.getOrDefault("vencendo_hoje", BigDecimal.ZERO)), MARKET_ORANGE));
        resumo.add(metricCard("Atrasado", moneyText(dueSummary.getOrDefault("atrasado", BigDecimal.ZERO)), MARKET_RED));
        panel.add(resumo);
        panel.add(Box.createVerticalStrut(12));

        JPanel form = new JPanel(new GridLayout(0, 4, 8, 8));
        form.setOpaque(false);
        JComboBox<String> tipo = new JComboBox<>(new String[]{"PAGAR", "RECEBER"});
        JTextField descricao = new JTextField();
        JTextField parceiro = new JTextField();
        JTextField categoria = new JTextField();
        JTextField valor = new JTextField("0");
        JTextField vencimento = new JTextField(LocalDate.now().plusDays(7).toString());
        JTextField observacao = new JTextField();
        form.add(label("Tipo")); form.add(tipo);
        form.add(label("Descricao")); form.add(descricao);
        form.add(label("Parceiro")); form.add(parceiro);
        form.add(label("Categoria")); form.add(categoria);
        form.add(label("Valor")); form.add(valor);
        form.add(label("Vencimento AAAA-MM-DD")); form.add(vencimento);
        form.add(label("Observacao")); form.add(observacao);
        JButton salvar = button("Salvar lancamento");
        salvar.addActionListener(e -> {
            try {
                requireFinanceAccess();
                long id = financeService.createEntry(new DesktopFinanceService.FinanceEntryRequest(
                        tipo.getSelectedItem().toString(),
                        descricao.getText(),
                        parceiro.getText(),
                        categoria.getText(),
                        money(valor.getText()),
                        vencimento.getText(),
                        observacao.getText()
                ), user.id);
                audit("FINANCEIRO_CADASTRO", "Lancamento #" + id + " | " + tipo.getSelectedItem());
                refreshFrame();
            } catch (Exception ex) { error(ex); }
        });

        JButton baixar = button("Baixar lancamento");
        baixar.addActionListener(e -> baixarLancamentoFinanceiro());

        panel.add(section("Novo lancamento", formWithAction(form, salvar)));
        panel.add(Box.createVerticalStrut(12));
        panel.add(baixar);
        panel.add(Box.createVerticalStrut(12));
        panel.add(section("Pendencias por vencimento", table("""
            select id as ID, tipo as Tipo, descricao as Descricao, coalesce(parceiro,'-') as Parceiro,
                   valor_total as Total, valor_baixado as Baixado, (valor_total-valor_baixado) as Aberto,
                   vencimento as Vencimento, status as Status
            from financeiro_lancamentos
            where status in ('ABERTO','PARCIAL')
            order by date(vencimento), id
            """)));
        panel.add(section("Lancamentos recentes", table("""
            select id as ID, tipo as Tipo, descricao as Descricao, valor_total as Total, valor_baixado as Baixado,
                   forma_baixa as Forma, status as Status, vencimento as Vencimento, baixado_em as BaixadoEm
            from financeiro_lancamentos
            order by id desc
            limit 40
            """)));
        return panel;
    }

    private JPanel relatoriosPanel() {
        JPanel panel = page();
        panel.add(section("Fechamento diario consolidado", summaryTable(todayCashSummary())));
        panel.add(section("Fluxo financeiro do dia", table("""
            select id as ID, tipo as Tipo, descricao as Descricao, valor_total as Total, valor_baixado as Baixado,
                   forma_baixa as Forma, baixado_em as Data
            from financeiro_lancamentos
            where baixado_em is not null and date(baixado_em)=date('now')
            order by id desc
            """)));
        panel.add(section("Comprovantes gerados", table("""
            select cv.venda_id as Venda, cv.arquivo_txt as TXT, cv.arquivo_pdf as PDF, cv.gerado_em as GeradoEm
            from comprovantes_venda cv
            order by cv.id desc
            limit 20
            """)));
        panel.add(section("Devolucoes de hoje", table("""
            select d.id as Devolucao, d.venda_id as Venda, d.tipo as Tipo, d.forma_destino as Destino, d.valor_total as Valor, d.criado_em as Data
            from devolucoes d
            where date(d.criado_em)=date('now')
            order by d.id desc
            """)));
        panel.add(section("Vendas de hoje", table("""
            select v.id as Venda, datetime(v.timestamp) as Data, c.numero as Caixa, u.nome as Operador, v.total as Total, v.forma_pagamento as Pagamento
            from vendas v join caixas c on c.id=v.caixa_id join usuarios u on u.id=v.operador_id
            where date(v.timestamp)=date('now') order by v.id desc
            """)));
        panel.add(section("Produtos mais vendidos", table("""
            select p.nome as Produto, sum(vi.quantidade) as Quantidade, sum(vi.quantidade*vi.preco_unitario) as Total
            from venda_itens vi join produtos p on p.id=vi.produto_id join vendas v on v.id=vi.venda_id
            where v.status='CONCLUIDA' group by p.id order by Quantidade desc limit 20
            """)));
        panel.add(section("Lucro por dia", table("""
            select date(v.timestamp) as Dia, sum(vi.quantidade*vi.preco_unitario) as Receita, sum(vi.quantidade*vi.custo_unitario) as Custo,
            sum(vi.quantidade*(vi.preco_unitario-vi.custo_unitario)) as Lucro
            from venda_itens vi join vendas v on v.id=vi.venda_id where v.status='CONCLUIDA' group by date(v.timestamp) order by Dia desc
            """)));
        panel.add(section("Fiado em aberto", table("select c.nome as Cliente, sum(f.valor-f.valor_pago) as Aberto from fiado f join clientes c on c.id=f.cliente_id where f.status='ABERTO' group by c.id")));
        panel.add(section("Validades proximas por lote", table("""
            select p.nome as Produto, coalesce(e.lote,'-') as Lote, e.validade as Validade, e.quantidade as Quantidade, e.documento as Documento
            from entradas_estoque e join produtos p on p.id = e.produto_id
            where e.validade is not null and date(e.validade) <= date('now','+30 day')
            order by date(e.validade), p.nome
            """)));
        return panel;
    }

    private void openCaixa(String fundo) {
        try {
            requirePdvAccess();
            Item item = (Item) caixaCombo.getSelectedItem();
            BigDecimal fundoInicial = money(fundo);
            BusinessRules.requireNonNegative(fundoInicial, "Fundo de caixa");
            Map<String, Object> caixa = one("select c.*, u.nome as operador from caixas c left join usuarios u on u.id=c.operador_atual_id where c.id=?", item.id);
            Object operadorId = caixa.get("operador_atual_id");
            if ("ABERTO".equals(caixa.get("status")) && operadorId != null && ((Number) operadorId).longValue() != user.id) {
                msg("Caixa em uso por " + caixa.get("operador"));
                return;
            }
            update("update caixas set status='ABERTO', operador_atual_id=?, abertura_valor=?, abertura_timestamp=? where id=?",
                    user.id, fundoInicial, LocalDateTime.now().toString(), item.id);
            audit("ABERTURA_CAIXA", item.text);
            msg("Caixa aberto.");
            refreshFrame();
        } catch (Exception ex) { error(ex); }
    }

    private void addCart(String codigo, String qtdText) {
        try {
            requirePdvAccess();
            BusinessRules.requireNotBlank(codigo, "Codigo ou descricao");
            Map<String, Object> p = one("""
                select * from produtos
                where ativo=1 and (codigo_barras=? or sku=? or codigo_interno=? or lower(nome) like lower(?))
                order by case when codigo_barras=? or sku=? or codigo_interno=? then 0 else 1 end
                limit 1
                """, codigo, codigo, codigo, "%" + codigo + "%", codigo, codigo, codigo);
            if (p == null) {
                msg("Produto nao encontrado. Confira codigo, descricao ou cadastro.");
                return;
            }
            BigDecimal qtd = money(qtdText);
            BusinessRules.requirePositive(qtd, "Quantidade");
            BigDecimal preco = money(p.get("preco_venda").toString());
            BusinessRules.validateSalePrice(preco, user.autorizaPrecoZero || intValue(p.get("permite_preco_zero")) == 1);
            cart.add(new CartItem(((Number) p.get("id")).longValue(), p.get("nome").toString(), qtd, preco));
            refreshCart();
        } catch (Exception ex) { error(ex); }
    }

    private void refreshCart() {
        cartModel.setRowCount(0);
        BigDecimal total = BigDecimal.ZERO;
        for (CartItem item : cart) {
            BigDecimal linha = item.qtd.multiply(item.preco);
            total = total.add(linha);
            cartModel.addRow(new Object[]{item.produtoId, item.nome, item.qtd, moneyText(item.preco), moneyText(linha)});
        }
        totalLabel.setText(moneyText(total));
        paymentFeedbackUpdater.run();
    }

    private void finalizarVenda(Map<String, BigDecimal> paymentInputs, String codigoValeTroca, String descontoText, String pin) {
        try {
            requirePdvAccess();
            if (cart.isEmpty()) {
                msg("Carrinho vazio.");
                return;
            }
            Item caixa = (Item) caixaCombo.getSelectedItem();
            BigDecimal desconto = money(descontoText);
            BigDecimal subtotal = cart.stream().map(i -> i.qtd.multiply(i.preco)).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (desconto.compareTo(BigDecimal.ZERO) > 0 && !managerPin(pin)) {
                BusinessRules.validateDiscount(subtotal, desconto, user.descontoMaximo);
            }
            if (desconto.compareTo(BigDecimal.ZERO) > 0 && managerPin(pin)) {
                BusinessRules.validateDiscount(subtotal, desconto, new BigDecimal("100"));
            }
            BigDecimal total = subtotal.subtract(desconto);
            BusinessRules.requirePositive(total, "Total da venda");
            Map<String, BigDecimal> pagamentos = PaymentAllocationService.validateAndNormalize(total, paymentInputs);
            Long clienteId = null;
            if (pagamentos.containsKey("FIADO") || pagamentos.containsKey("CREDITO_TROCA")) {
                Item cliente = (Item) clienteCombo.getSelectedItem();
                if (cliente != null) {
                    clienteId = cliente.id;
                }
            }
            if (pagamentos.containsKey("FIADO") && clienteId == null) {
                msg("Selecione um cliente quando houver valor em fiado.");
                return;
            }
            if (pagamentos.containsKey("CREDITO_TROCA") && (codigoValeTroca == null || codigoValeTroca.isBlank())) {
                msg("Informe o codigo do vale troca.");
                return;
            }
            final Long clienteIdFinal = clienteId;
            final BigDecimal totalFinal = total;
            final BigDecimal descontoFinal = desconto;
            final long caixaId = caixa.id;
            final String formaPagamento = PaymentAllocationService.paymentLabel(pagamentos);
            long vendaId = withTransaction(() -> {
                if (pagamentos.containsKey("CREDITO_TROCA")) {
                    returnService.consumeStoreCredit(codigoValeTroca, pagamentos.get("CREDITO_TROCA"), clienteIdFinal);
                }
                long id = insert("""
                    insert into vendas (caixa_id, operador_id, cliente_id, total, desconto, forma_pagamento, timestamp, status)
                    values (?, ?, ?, ?, ?, ?, ?, 'CONCLUIDA')
                    """, caixaId, user.id, clienteIdFinal, totalFinal, descontoFinal, formaPagamento, LocalDateTime.now().toString());
                for (CartItem item : cart) {
                    Map<String, Object> p = one("select nome, estoque_atual, preco_custo from produtos where id=?", item.produtoId);
                    BigDecimal estoqueAtual = money(p.get("estoque_atual").toString());
                    BusinessRules.ensureStockAvailable(estoqueAtual, item.qtd, p.get("nome").toString());
                    update("insert into venda_itens (venda_id, produto_id, quantidade, preco_unitario, custo_unitario) values (?, ?, ?, ?, ?)",
                            id, item.produtoId, item.qtd, item.preco, p.get("preco_custo"));
                    int changed = updateCount("update produtos set estoque_atual=estoque_atual-? where id=? and estoque_atual >= ?",
                            item.qtd, item.produtoId, item.qtd);
                    if (changed == 0) {
                        throw new AppException("Estoque insuficiente para " + p.get("nome") + ".");
                    }
                    update("insert into movimentacao_estoque (produto_id, tipo, quantidade, referencia_id, operador_id, timestamp, observacao) values (?, 'VENDA', ?, ?, ?, ?, ?)",
                            item.produtoId, item.qtd, id, user.id, LocalDateTime.now().toString(), "Venda #" + id);
                }
                for (Map.Entry<String, BigDecimal> pagamento : pagamentos.entrySet()) {
                    update("insert into venda_pagamentos (venda_id, forma, valor) values (?, ?, ?)", id, pagamento.getKey(), pagamento.getValue());
                }
                if (pagamentos.containsKey("FIADO")) {
                    update("insert into fiado (cliente_id, venda_id, valor, valor_pago, status, data_criacao) values (?, ?, ?, 0, 'ABERTO', ?)",
                            clienteIdFinal, id, pagamentos.get("FIADO"), LocalDateTime.now().toString());
                }
                return id;
            });
            DesktopReceiptService.ReceiptFiles receiptFiles = receiptService.generateForSale(vendaId);
            cart.clear();
            refreshCart();
            audit("VENDA", "Venda #" + vendaId + " | " + formaPagamento);
            msg("Venda #" + vendaId + " finalizada.\nPagamentos: " + formaPagamento
                    + "\nTXT: " + receiptFiles.txtFile().getAbsolutePath()
                    + "\nPDF: " + receiptFiles.pdfFile().getAbsolutePath());
        } catch (Exception ex) { error(ex); }
    }

    private void caixaOperacao(String tipo) {
        try {
            requirePdvAccess();
            String valor = JOptionPane.showInputDialog(frame, "Valor da " + tipo + ":");
            String motivo = JOptionPane.showInputDialog(frame, "Motivo:");
            BigDecimal valorOperacao = money(valor);
            BusinessRules.requirePositive(valorOperacao, "Valor da operacao");
            BusinessRules.requireNotBlank(motivo, "Motivo");
            if ("SANGRIA".equals(tipo)) {
                String pin = JOptionPane.showInputDialog(frame, "PIN do gerente:");
                if (!managerPin(pin)) {
                    msg("PIN invalido.");
                    return;
                }
            }
            Item caixa = (Item) caixaCombo.getSelectedItem();
            update("insert into caixa_operacoes (caixa_id, tipo, valor, motivo, operador_id, timestamp) values (?, ?, ?, ?, ?, ?)",
                    caixa.id, tipo, valorOperacao, motivo, user.id, LocalDateTime.now().toString());
            audit(tipo, motivo);
            msg("Operacao registrada.");
        } catch (Exception ex) { error(ex); }
    }

    private void cancelarUltima() {
        try {
            requirePdvAccess();
            String pin = JOptionPane.showInputDialog(frame, "PIN do gerente:");
            if (!managerPin(pin)) {
                msg("PIN invalido.");
                return;
            }
            String motivo = JOptionPane.showInputDialog(frame, "Motivo do cancelamento:");
            BusinessRules.requireNotBlank(motivo, "Motivo");
            Item caixa = (Item) caixaCombo.getSelectedItem();
            Map<String, Object> venda = one("select id from vendas where caixa_id=? and status='CONCLUIDA' order by id desc limit 1", caixa.id);
            if (venda == null) {
                msg("Nenhuma venda para cancelar.");
                return;
            }
            long vendaId = ((Number) venda.get("id")).longValue();
            withTransaction(() -> {
                for (Map<String, Object> item : rows("select produto_id, quantidade from venda_itens where venda_id=?", vendaId)) {
                    update("update produtos set estoque_atual=estoque_atual+? where id=?", item.get("quantidade"), item.get("produto_id"));
                    update("insert into movimentacao_estoque (produto_id, tipo, quantidade, referencia_id, operador_id, timestamp, observacao) values (?, 'CANCELAMENTO', ?, ?, ?, ?, ?)",
                            item.get("produto_id"), item.get("quantidade"), vendaId, user.id, LocalDateTime.now().toString(), motivo);
                }
                update("update vendas set status='CANCELADA' where id=?", vendaId);
                update("update fiado set status='CANCELADO' where venda_id=?", vendaId);
                return null;
            });
            audit("CANCELAR_VENDA", "Venda #" + vendaId + " - " + motivo);
            msg("Venda cancelada.");
        } catch (Exception ex) { error(ex); }
    }

    private void processarTrocaOuDevolucao() {
        try {
            requirePdvAccess();
            String pin = JOptionPane.showInputDialog(frame, "PIN do gerente:");
            if (!managerPin(pin)) {
                msg("PIN invalido.");
                return;
            }
            String vendaIdTexto = JOptionPane.showInputDialog(frame, "ID da venda original:");
            BusinessRules.requireNotBlank(vendaIdTexto, "Venda");
            long vendaId = Long.parseLong(vendaIdTexto);
            List<DesktopReturnService.ReturnableItem> itens = returnService.listReturnableItems(vendaId);
            if (itens.isEmpty()) {
                msg("Nao ha itens disponiveis para devolucao nessa venda.");
                return;
            }

            JComboBox<String> tipoCombo = new JComboBox<>(new String[]{"DEVOLUCAO", "TROCA"});
            JComboBox<String> formaCombo = new JComboBox<>(new String[]{"DINHEIRO", "PIX", "DEBITO", "CREDITO", "ABATER_FIADO"});
            JTextField motivo = new JTextField("Troca / devolucao autorizada");
            JPanel panel = new JPanel(new GridLayout(0, 2, 8, 8));
            panel.add(label("Tipo"));
            panel.add(tipoCombo);
            panel.add(label("Destino financeiro"));
            panel.add(formaCombo);
            panel.add(label("Motivo"));
            panel.add(motivo);

            Map<DesktopReturnService.ReturnableItem, JTextField> quantidades = new LinkedHashMap<>();
            for (DesktopReturnService.ReturnableItem item : itens) {
                JTextField campo = new JTextField("0");
                panel.add(label(item.nome() + " | disponivel: " + item.quantidadeDisponivel() + " | unitario: " + moneyText(item.precoUnitario())));
                panel.add(campo);
                quantidades.put(item, campo);
            }

            int option = JOptionPane.showConfirmDialog(frame, panel, "Troca / devolucao", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (option != JOptionPane.OK_OPTION) {
                return;
            }

            List<DesktopReturnService.ReturnItemRequest> itensSelecionados = new ArrayList<>();
            for (Map.Entry<DesktopReturnService.ReturnableItem, JTextField> entry : quantidades.entrySet()) {
                BigDecimal quantidade = money(entry.getValue().getText());
                if (quantidade.compareTo(BigDecimal.ZERO) > 0) {
                    itensSelecionados.add(new DesktopReturnService.ReturnItemRequest(entry.getKey().vendaItemId(), quantidade));
                }
            }
            if (itensSelecionados.isEmpty()) {
                msg("Informe ao menos uma quantidade para troca ou devolucao.");
                return;
            }

            Item caixa = (Item) caixaCombo.getSelectedItem();
            String tipo = tipoCombo.getSelectedItem().toString();
            String forma = "TROCA".equals(tipo) ? "VALE_TROCA" : formaCombo.getSelectedItem().toString();
            DesktopReturnService.ReturnResult result = returnService.processReturn(
                    new DesktopReturnService.ReturnRequest(vendaId, caixa.id, tipo, forma, motivo.getText(), itensSelecionados),
                    user.id
            );
            audit(tipo, "Venda #" + vendaId + " | devolucao #" + result.devolucaoId() + " | " + result.formaDestino());
            if (result.codigoCredito() != null) {
                msg("Troca registrada.\nValor: " + moneyText(result.valorTotal()) + "\nVale troca: " + result.codigoCredito());
            } else {
                msg("Devolucao registrada.\nValor: " + moneyText(result.valorTotal()) + "\nDestino: " + result.formaDestino());
            }
            refreshFrame();
        } catch (Exception ex) { error(ex); }
    }

    private void reemitirComprovante() {
        try {
            requirePdvAccess();
            String vendaIdTexto = JOptionPane.showInputDialog(frame, "ID da venda para reemitir comprovante:");
            BusinessRules.requireNotBlank(vendaIdTexto, "Venda");
            DesktopReceiptService.ReceiptFiles files = receiptService.generateForSale(Long.parseLong(vendaIdTexto.trim()));
            audit("REEMITIR_COMPROVANTE", "Venda #" + vendaIdTexto.trim());
            msg("Comprovante reemitido.\nTXT: " + files.txtFile().getAbsolutePath() + "\nPDF: " + files.pdfFile().getAbsolutePath());
        } catch (Exception ex) { error(ex); }
    }

    private void fecharCaixa() {
        try {
            requirePdvAccess();
            String contado = JOptionPane.showInputDialog(frame, "Dinheiro contado:");
            Item caixa = (Item) caixaCombo.getSelectedItem();
            Map<String, BigDecimal> resumo = cashReportService.dailySummary(caixa.id, LocalDate.now());
            BigDecimal esperado = resumo.get("esperado_dinheiro");
            BigDecimal contadoValor = money(contado);
            BusinessRules.requireNonNegative(contadoValor, "Dinheiro contado");
            BigDecimal diferenca = contadoValor.subtract(esperado);
            update("insert into caixa_operacoes (caixa_id, tipo, valor, motivo, operador_id, timestamp) values (?, 'FECHAMENTO', ?, ?, ?, ?)",
                    caixa.id, contadoValor, "Fechamento. Esperado: " + moneyText(esperado) + ". Diferenca: " + moneyText(diferenca), user.id, LocalDateTime.now().toString());
            update("update caixas set status='FECHADO', operador_atual_id=null where id=?", caixa.id);
            msg("""
                Fechamento registrado.
                Esperado: %s
                Contado: %s
                Diferenca: %s
                Dinheiro: %s
                PIX: %s
                Debito: %s
                Credito: %s
                Devolucao dinheiro: %s
                Sangria: %s
                Suprimento: %s
                """.formatted(
                    moneyText(esperado),
                    moneyText(contadoValor),
                    moneyText(diferenca),
                    moneyText(resumo.get("dinheiro")),
                    moneyText(resumo.get("pix")),
                    moneyText(resumo.get("debito")),
                    moneyText(resumo.get("credito")),
                    moneyText(resumo.get("devolucao_dinheiro")),
                    moneyText(resumo.get("sangria")),
                    moneyText(resumo.get("suprimento"))
            ));
            refreshFrame();
        } catch (Exception ex) { error(ex); }
    }

    private void ajustarEstoque() {
        try {
            requireInventoryAccess();
            String produtoId = JOptionPane.showInputDialog(frame, "ID do produto:");
            String tipo = JOptionPane.showInputDialog(frame, "Tipo: ENTRADA, SAIDA ou AJUSTE:", "ENTRADA");
            String qtd = JOptionPane.showInputDialog(frame, "Quantidade:");
            String motivo = JOptionPane.showInputDialog(frame, "Motivo:");
            BusinessRules.requireNotBlank(produtoId, "Produto");
            BusinessRules.requireNotBlank(tipo, "Tipo");
            BusinessRules.requireNotBlank(motivo, "Motivo");
            BigDecimal quantidade = money(qtd);
            BusinessRules.requirePositive(quantidade, "Quantidade");
            inventoryService.registerMovement(Long.parseLong(produtoId), tipo.toUpperCase(Locale.ROOT), quantidade, motivo, user.id);
            audit("AJUSTE_ESTOQUE", "Produto " + produtoId + " | " + tipo.toUpperCase(Locale.ROOT));
            refreshFrame();
        } catch (Exception ex) { error(ex); }
    }

    private void reconciliarInventario() {
        try {
            requireInventoryAccess();
            String produtoId = JOptionPane.showInputDialog(frame, "ID do produto:");
            String saldoContado = JOptionPane.showInputDialog(frame, "Saldo contado:");
            String motivo = JOptionPane.showInputDialog(frame, "Motivo da contagem:", "Inventario geral");
            inventoryService.reconcileInventory(
                    new DesktopInventoryService.InventoryCountRequest(
                            Long.parseLong(produtoId.trim()),
                            money(saldoContado),
                            motivo
                    ),
                    user.id
            );
            audit("INVENTARIO_AJUSTE", "Produto " + produtoId);
            refreshFrame();
        } catch (Exception ex) { error(ex); }
    }

    private void registrarPerdaOuQuebra() {
        try {
            requireInventoryAccess();
            String produtoId = JOptionPane.showInputDialog(frame, "ID do produto:");
            String tipo = JOptionPane.showInputDialog(frame, "Tipo: PERDA ou QUEBRA", "PERDA");
            String quantidade = JOptionPane.showInputDialog(frame, "Quantidade:");
            String motivo = JOptionPane.showInputDialog(frame, "Motivo:");
            inventoryService.registerMovement(
                    Long.parseLong(produtoId.trim()),
                    tipo.toUpperCase(Locale.ROOT),
                    money(quantidade),
                    motivo,
                    user.id
            );
            audit("PERDA_QUEBRA", "Produto " + produtoId + " | " + tipo.toUpperCase(Locale.ROOT));
            refreshFrame();
        } catch (Exception ex) { error(ex); }
    }

    private void importarXml() {
        JFileChooser chooser = new JFileChooser(xmlInboxDir().toFile());
        chooser.setDialogTitle("Selecionar XML NF-e da pasta padrao");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Arquivos XML", "xml"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            requireInventoryAccess();
            File file = persistXmlInInbox(chooser.getSelectedFile());
            importXmlFile(file);
            refreshFrame();
        } catch (Exception ex) { error(ex); }
    }

    private void importarUltimoXmlDaPasta() {
        try {
            requireInventoryAccess();
            Path inbox = xmlInboxDir();
            Files.createDirectories(inbox);
            List<Path> xmlFiles;
            try (var stream = Files.list(inbox)) {
                xmlFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                        .sorted((a, b) -> {
                            try {
                                return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                            } catch (Exception ignored) {
                                return 0;
                            }
                        })
                        .toList();
            }
            if (xmlFiles.isEmpty()) {
                msg("Nenhum XML encontrado em " + inbox.toAbsolutePath());
                return;
            }
            File file = xmlFiles.get(0).toFile();
            importXmlFile(file);
            msg("XML importado: " + file.getName());
            refreshFrame();
        } catch (Exception ex) { error(ex); }
    }

    private void importXmlFile(File file) throws Exception {
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
        doc.getDocumentElement().normalize();
        String cnpj = first(doc, "CNPJ");
        String fornecedor = first(doc, "xNome");
        update("insert into fornecedores (razao_social, nome_fantasia, cnpj) values (?, ?, ?) on conflict(cnpj) do update set razao_social=excluded.razao_social",
                fornecedor.isBlank() ? "Fornecedor NF-e" : fornecedor, fornecedor, cnpj);
        Long fornecedorId = (Long) one("select id from fornecedores where cnpj=?", cnpj).get("id");
        var dets = doc.getElementsByTagName("det");
        for (int i = 0; i < dets.getLength(); i++) {
            Element prod = (Element) ((Element) dets.item(i)).getElementsByTagName("prod").item(0);
            String codigo = text(prod, "cEAN");
            String nome = text(prod, "xProd");
            BigDecimal qtd = money(text(prod, "qCom"));
            BigDecimal custo = money(text(prod, "vUnCom"));
            BusinessRules.requireNotBlank(nome, "Produto da NF-e");
            BusinessRules.requirePositive(qtd, "Quantidade da NF-e");
            BusinessRules.requireNonNegative(custo, "Custo da NF-e");
            Map<String, Object> existente = one("select id from produtos where codigo_barras=?", codigo);
            if (existente == null) {
                String codigoInterno = nextInternalCode();
                long produtoId = insert("""
                    insert into produtos (nome, codigo_barras, sku, codigo_interno, categoria, unidade, preco_custo, preco_venda, estoque_atual, estoque_minimo, fornecedor_id, validade, observacoes, ativo)
                    values (?, ?, ?, ?, 'Mercearia', 'un', ?, ?, ?, 1, ?, ?, ?, 1)
                    """, nome, codigo, codigo, codigoInterno, custo, markupPrice(custo), qtd, fornecedorId, null, "Criado por importacao XML NF-e");
                recordPriceHistory(produtoId, null, custo, null, markupPrice(custo), "Cadastro por XML NF-e");
                update("insert into movimentacao_estoque (produto_id, tipo, quantidade, operador_id, timestamp, observacao) values (?, 'ENTRADA', ?, ?, ?, ?)",
                        produtoId, qtd, user.id, LocalDateTime.now().toString(), "XML NF-e");
            } else {
                long produtoId = ((Number) existente.get("id")).longValue();
                Map<String, Object> produtoAtual = one("select estoque_atual, preco_custo, preco_venda from produtos where id=?", produtoId);
                BigDecimal custoMedio = weightedAverageCost(money(produtoAtual.get("estoque_atual").toString()), money(produtoAtual.get("preco_custo").toString()), qtd, custo);
                recordPriceHistory(produtoId, money(produtoAtual.get("preco_custo").toString()), custoMedio,
                        money(produtoAtual.get("preco_venda").toString()), money(produtoAtual.get("preco_venda").toString()),
                        "Atualizacao de custo por XML NF-e");
                update("update produtos set estoque_atual=estoque_atual+?, preco_custo=?, fornecedor_id=? where id=?", qtd, custoMedio, fornecedorId, produtoId);
                update("insert into movimentacao_estoque (produto_id, tipo, quantidade, operador_id, timestamp, observacao) values (?, 'ENTRADA', ?, ?, ?, ?)",
                        produtoId, qtd, user.id, LocalDateTime.now().toString(), "XML NF-e");
            }
        }
        update("insert into notas_fiscais (fornecedor_id, numero_nf, data, xml_path, total, importado_em) values (?, ?, ?, ?, ?, ?)",
                fornecedorId, first(doc, "nNF"), first(doc, "dhEmi"), file.getAbsolutePath(), money(first(doc, "vNF")), LocalDateTime.now().toString());
        msg(dets.getLength() + " itens importados.");
    }

    private Path xmlInboxDir() {
        return Path.of(XML_INBOX_DIR);
    }

    private String resolveDesktopDbUrl() {
        String envUrl = System.getenv(DB_URL_ENV);
        if (envUrl != null && !envUrl.isBlank()) {
            return envUrl.trim();
        }
        File configFile = new File(DB_URL_CONFIG);
        if (configFile.exists()) {
            try (FileInputStream in = new FileInputStream(configFile)) {
                Properties props = new Properties();
                props.load(in);
                String fileUrl = props.getProperty("mercado.db.url", "").trim();
                if (!fileUrl.isBlank()) {
                    return fileUrl;
                }
            } catch (Exception ignored) {
                // Falls back to local database URL when config cannot be read.
            }
        }
        return "jdbc:sqlite:data/mercado-tonico.db";
    }

    private File persistXmlInInbox(File selectedFile) throws Exception {
        Path inbox = xmlInboxDir();
        Files.createDirectories(inbox);
        Path selected = selectedFile.toPath().toAbsolutePath().normalize();
        Path target = inbox.resolve(selectedFile.getName()).toAbsolutePath().normalize();
        if (!selected.equals(target)) {
            Files.copy(selected, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target.toFile();
    }

    private void abrirPastaXml() {
        try {
            Path inbox = xmlInboxDir();
            Files.createDirectories(inbox);
            Desktop.getDesktop().open(inbox.toFile());
        } catch (Exception ex) {
            error(ex);
        }
    }

    private void pagarFiado() {
        try {
            requireFiadoAccess();
            String id = JOptionPane.showInputDialog(frame, "ID do fiado:");
            String valor = JOptionPane.showInputDialog(frame, "Valor pago:");
            BigDecimal valorPago = money(valor);
            BusinessRules.requirePositive(valorPago, "Valor pago");
            update("insert into fiado_pagamentos (fiado_id, valor, data, operador_id) values (?, ?, ?, ?)",
                    Long.parseLong(id), valorPago, LocalDateTime.now().toString(), user.id);
            update("update fiado set valor_pago=valor_pago+? where id=?", valorPago, Long.parseLong(id));
            update("update fiado set status='PAGO' where id=? and valor_pago >= valor", Long.parseLong(id));
            refreshFrame();
        } catch (Exception ex) { error(ex); }
    }

    private void baixarLancamentoFinanceiro() {
        try {
            requireFinanceAccess();
            String id = JOptionPane.showInputDialog(frame, "ID do lancamento:");
            String valor = JOptionPane.showInputDialog(frame, "Valor da baixa:");
            String forma = JOptionPane.showInputDialog(frame, "Forma da baixa: DINHEIRO, PIX, DEBITO, CREDITO");
            String observacao = JOptionPane.showInputDialog(frame, "Observacao da baixa:");
            financeService.settle(Long.parseLong(id), money(valor), forma, observacao);
            audit("FINANCEIRO_BAIXA", "Lancamento #" + id + " | " + forma);
            refreshFrame();
        } catch (Exception ex) { error(ex); }
    }

    private boolean managerPin(String pin) throws Exception {
        for (Map<String, Object> row : rows("select pin_hash from usuarios where role in ('ADMIN','GERENTE') and ativo=1 and pin_hash is not null")) {
            if (encoder.matches(pin == null ? "" : pin, row.get("pin_hash").toString())) {
                return true;
            }
        }
        return false;
    }

    private JTable table(String sql, Object... args) {
        try {
            List<Map<String, Object>> data = rows(sql, args);
            Vector<String> cols = new Vector<>();
            Vector<Vector<Object>> lines = new Vector<>();
            if (!data.isEmpty()) {
                cols.addAll(data.get(0).keySet());
            }
            for (Map<String, Object> row : data) {
                Vector<Object> line = new Vector<>();
                for (Object value : row.values()) {
                    if (value instanceof Number && value.toString().contains(".")) {
                        line.add(value);
                    } else if (value != null && value.toString().matches("\\d{4}-\\d{2}-\\d{2}T.*")) {
                        line.add(LocalDateTime.parse(value.toString()).format(BR_DATE_TIME));
                    } else {
                        line.add(value);
                    }
                }
                lines.add(line);
            }
            JTable table = new JTable(new DefaultTableModel(lines, cols));
            table.setAutoCreateRowSorter(true);
            styleTable(table);
            return table;
        } catch (Exception e) {
            error(e);
            return new JTable();
        }
    }

    private JComboBox<Item> combo(String sql) {
        JComboBox<Item> combo = new JComboBox<>();
        combo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        try {
            for (Map<String, Object> row : rows(sql)) {
                combo.addItem(new Item(((Number) row.values().toArray()[0]).longValue(), row.values().toArray()[1].toString()));
            }
        } catch (Exception e) { error(e); }
        return combo;
    }

    private JPanel page() {
        JPanel panel = new JPanel();
        panel.setBackground(MARKET_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private JPanel section(String title, Component child) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(PANEL_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(214, 221, 229)),
                new EmptyBorder(10, 10, 10, 10)));
        JLabel heading = sectionTitle(title);
        panel.add(heading, BorderLayout.NORTH);
        if (child instanceof JTable table) {
            styleTable(table);
            panel.add(new JScrollPane(table), BorderLayout.CENTER);
        } else {
            panel.add(child, BorderLayout.CENTER);
        }
        return panel;
    }

    private JPanel formWithAction(JPanel form, JButton action) {
        JPanel wrapper = new JPanel(new BorderLayout(0, 10));
        wrapper.setOpaque(false);
        wrapper.add(form, BorderLayout.CENTER);
        wrapper.add(action, BorderLayout.SOUTH);
        return wrapper;
    }

    private Map<String, BigDecimal> paymentInputs(JTextField dinheiro, JTextField debito, JTextField credito, JTextField pix,
                                                  JTextField fiado, JTextField creditoTroca) {
        Map<String, BigDecimal> inputs = new LinkedHashMap<>();
        inputs.put("DINHEIRO", money(dinheiro.getText()));
        inputs.put("DEBITO", money(debito.getText()));
        inputs.put("CREDITO", money(credito.getText()));
        inputs.put("PIX", money(pix.getText()));
        inputs.put("FIADO", money(fiado.getText()));
        inputs.put("CREDITO_TROCA", money(creditoTroca.getText()));
        return inputs;
    }

    private Map<String, BigDecimal> todayCashSummary() {
        try {
            return cashReportService.dailySummary(null, LocalDate.now());
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Runnable bindPaymentFeedback(JLabel paymentStatus, JTextField desconto, JTextField... fields) {
        Runnable updater = () -> {
            BigDecimal subtotal = cart.stream().map(i -> i.qtd.multiply(i.preco)).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal total = subtotal.subtract(safeMoneyText(desconto.getText()));
            BigDecimal pago = BigDecimal.ZERO;
            for (JTextField field : fields) {
                pago = pago.add(safeMoneyText(field.getText()));
            }
            BigDecimal diferenca = total.subtract(pago);
            if (diferenca.compareTo(BigDecimal.ZERO) > 0) {
                paymentStatus.setForeground(MARKET_ORANGE);
                paymentStatus.setText("Faltante: " + moneyText(diferenca));
            } else if (diferenca.compareTo(BigDecimal.ZERO) < 0) {
                paymentStatus.setForeground(MARKET_RED);
                paymentStatus.setText("Excedente: " + moneyText(diferenca.abs()));
            } else {
                paymentStatus.setForeground(MARKET_GREEN_2);
                paymentStatus.setText("Pagamento conferido");
            }
        };
        DocumentListener listener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updater.run(); }
            @Override public void removeUpdate(DocumentEvent e) { updater.run(); }
            @Override public void changedUpdate(DocumentEvent e) { updater.run(); }
        };
        desconto.getDocument().addDocumentListener(listener);
        for (JTextField field : fields) {
            field.getDocument().addDocumentListener(listener);
        }
        updater.run();
        return updater;
    }

    private BigDecimal safeMoneyText(String text) {
        try {
            return money(text);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private void bindPdvShortcuts(JComponent root, JTextField codigo, JButton limpar, JButton finalizar,
                                  JButton suprimento, JButton sangria, JButton trocaDevolucao, JButton comprovante) {
        bindShortcut(root, "focusCodigo", KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), codigo::requestFocusInWindow);
        bindShortcut(root, "finalizarVenda", KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), finalizar::doClick);
        bindShortcut(root, "limparCarrinho", KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), limpar::doClick);
        bindShortcut(root, "suprimento", KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), suprimento::doClick);
        bindShortcut(root, "sangria", KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0), sangria::doClick);
        bindShortcut(root, "trocaDevolucao", KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0), trocaDevolucao::doClick);
        bindShortcut(root, "reemitirComprovante", KeyStroke.getKeyStroke(KeyEvent.VK_F10, 0), comprovante::doClick);
        bindShortcut(root, "limparCodigo", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), () -> {
            codigo.setText("");
            codigo.requestFocusInWindow();
        });
    }

    private void bindShortcut(JComponent root, String key, KeyStroke shortcut, Runnable action) {
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(shortcut, key);
        root.getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
    }

    private Map<String, BigDecimal> financeDueSummary() {
        try {
            return financeService.dueSummary();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private JTable summaryTable(Map<String, BigDecimal> summary) {
        DefaultTableModel model = new DefaultTableModel(new Object[]{"Indicador", "Valor"}, 0);
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("vendas_totais", "Vendas totais");
        labels.put("abertura", "Abertura");
        labels.put("dinheiro", "Dinheiro");
        labels.put("pix", "PIX");
        labels.put("debito", "Debito");
        labels.put("credito", "Credito");
        labels.put("fiado", "Fiado gerado");
        labels.put("devolucao_dinheiro", "Devolucao em dinheiro");
        labels.put("devolucao_pix", "Devolucao em PIX");
        labels.put("devolucao_debito", "Devolucao em debito");
        labels.put("devolucao_credito", "Devolucao em credito");
        labels.put("abate_fiado", "Abate em fiado");
        labels.put("vale_troca_emitido", "Vale troca emitido");
        labels.put("recebimentos_fiado", "Recebimentos de fiado");
        labels.put("contas_pagas", "Contas pagas");
        labels.put("contas_recebidas", "Contas recebidas");
        labels.put("contas_pagas_dinheiro", "Contas pagas em dinheiro");
        labels.put("contas_recebidas_dinheiro", "Contas recebidas em dinheiro");
        labels.put("sangria", "Sangria");
        labels.put("suprimento", "Suprimento");
        labels.put("esperado_dinheiro", "Esperado em dinheiro");
        labels.put("contado_fechamento", "Contado no fechamento");
        labels.put("divergencia", "Divergencia");
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            model.addRow(new Object[]{entry.getValue(), moneyText(summary.getOrDefault(entry.getKey(), BigDecimal.ZERO))});
        }
        JTable table = new JTable(model);
        styleTable(table);
        return table;
    }

    private JPanel formPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 8));
        panel.setPreferredSize(new Dimension(360, 90));
        return panel;
    }

    private JLabel title(String text) {
        JLabel label = new JLabel(text);
        label.setFont(TITLE_FONT);
        label.setForeground(new Color(24, 39, 58));
        label.setBorder(new EmptyBorder(0, 0, 12, 0));
        return label;
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.BOLD, 13));
        label.setForeground(new Color(50, 63, 75));
        return label;
    }

    private JButton button(String text) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setFont(new Font("Segoe UI", Font.BOLD, 14));
        button.setForeground(Color.WHITE);
        button.setBackground(buttonColor(text));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(buttonColor(text).darker()),
                new EmptyBorder(10, 12, 10, 12)
        ));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        return button;
    }

    private JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(SECTION_FONT);
        label.setForeground(MARKET_GREEN);
        label.setBorder(new EmptyBorder(0, 0, 8, 0));
        return label;
    }

    private JPanel appHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(DARK_SURFACE);
        header.setBorder(new EmptyBorder(14, 20, 14, 20));
        JLabel brand = new JLabel("Mercado do Tonico");
        brand.setForeground(Color.WHITE);
        brand.setFont(new Font("Segoe UI", Font.BOLD, 31));
        JLabel subtitle = new JLabel("Sistema desktop de mercado  |  " + user.nome + "  |  " + user.role);
        subtitle.setForeground(new Color(197, 215, 235));
        subtitle.setFont(new Font("Segoe UI", Font.BOLD, 14));
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(brand);
        left.add(subtitle);
        JLabel clock = new JLabel(LocalDateTime.now().format(BR_DATE_TIME));
        clock.setForeground(new Color(156, 244, 211));
        clock.setFont(new Font("Segoe UI", Font.BOLD, 20));
        header.add(left, BorderLayout.WEST);
        header.add(clock, BorderLayout.EAST);
        return header;
    }

    private JPanel metricCard(String label, String value, Color color) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(PANEL_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(205, 215, 228)),
                new EmptyBorder(14, 16, 14, 16)));
        JLabel l = new JLabel(label);
        l.setFont(new Font("Segoe UI", Font.BOLD, 14));
        l.setForeground(new Color(82, 96, 110));
        JLabel v = new JLabel(value);
        v.setFont(new Font("Segoe UI", Font.BOLD, 30));
        v.setForeground(color);
        JPanel stripe = new JPanel();
        stripe.setBackground(color);
        stripe.setPreferredSize(new Dimension(8, 0));
        card.add(stripe, BorderLayout.WEST);
        card.add(l, BorderLayout.NORTH);
        card.add(v, BorderLayout.CENTER);
        return card;
    }

    private Color buttonColor(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("cancelar") || lower.contains("fechar") || lower.contains("sangria")) {
            return MARKET_RED;
        }
        if (lower.contains("limpar")) {
            return new Color(82, 96, 110);
        }
        if (lower.contains("suprimento")) {
            return MARKET_ORANGE;
        }
        return MARKET_GREEN_2;
    }

    private void styleTable(JTable table) {
        table.setRowHeight(34);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 14));
        table.getTableHeader().setBackground(DARK_SURFACE_2);
        table.getTableHeader().setForeground(new Color(231, 240, 250));
        table.setGridColor(new Color(214, 224, 235));
        table.setSelectionBackground(new Color(188, 233, 218));
        table.setSelectionForeground(new Color(18, 34, 42));
        table.setShowVerticalLines(false);
    }

    private void setupLookAndFeel() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
            UIManager.put("control", MARKET_BG);
            UIManager.put("TabbedPane.contentAreaColor", MARKET_BG);
            UIManager.put("TabbedPane.selected", PANEL_BG);
            UIManager.put("TabbedPane.background", new Color(216, 225, 236));
            UIManager.put("TabbedPane.foreground", new Color(33, 48, 65));
            UIManager.put("OptionPane.messageFont", new Font("Segoe UI", Font.PLAIN, 14));
            UIManager.put("OptionPane.buttonFont", new Font("Segoe UI", Font.BOLD, 13));
            UIManager.put("ScrollBar.width", scale(14));
        } catch (Exception ignored) {
            // Default Swing theme is acceptable if Nimbus is not available.
        }
    }

    private int scale(int value) {
        return (int) Math.max(10, Math.round(value * uiScaleFactor()));
    }

    private double uiScaleFactor() {
        int h = screenSize.height;
        if (h <= 768) {
            return 0.92;
        }
        if (h <= 900) {
            return 0.97;
        }
        if (h >= 1200) {
            return 1.06;
        }
        return 1.0;
    }

    private void stylePdvField(JTextField field) {
        field.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        field.setBackground(Color.WHITE);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(194, 206, 220)),
                new EmptyBorder(8, 8, 8, 8)
        ));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
    }

    private JPanel shortcutChip(String key, String label) {
        JPanel chip = new JPanel(new BorderLayout(6, 0));
        chip.setBackground(new Color(240, 246, 252));
        chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(202, 214, 227)),
                new EmptyBorder(6, 8, 6, 8)
        ));
        JLabel keyLabel = new JLabel(key);
        keyLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        keyLabel.setForeground(MARKET_GREEN);
        JLabel textLabel = new JLabel(label);
        textLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        textLabel.setForeground(new Color(55, 73, 91));
        chip.add(keyLabel, BorderLayout.WEST);
        chip.add(textLabel, BorderLayout.CENTER);
        return chip;
    }

    private void refreshFrame() {
        frame.dispose();
        buildFrame();
    }

    private void requirePdvAccess() {
        if (!UserPermissions.canAccessPdv(user.role)) {
            throw new AppException("Seu perfil nao possui acesso ao PDV.");
        }
    }

    private void requireInventoryAccess() {
        if (!UserPermissions.canAccessInventory(user.role)) {
            throw new AppException("Seu perfil nao possui acesso ao estoque.");
        }
    }

    private void requireFiadoAccess() {
        if (!UserPermissions.canAccessFiado(user.role)) {
            throw new AppException("Seu perfil nao possui acesso ao fiado.");
        }
    }

    private void requireFinanceAccess() {
        if (!UserPermissions.canAccessFinance(user.role)) {
            throw new AppException("Seu perfil nao possui acesso ao financeiro.");
        }
    }

    private int safeInt(String sql) {
        try {
            return oneInt(sql);
        } catch (Exception e) {
            return 0;
        }
    }

    private BigDecimal safeMoney(String sql) {
        try {
            Object value = one(sql).values().iterator().next();
            return value == null ? BigDecimal.ZERO : money(value.toString());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private void exec(String sql) throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    private void update(String sql, Object... args) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        }
    }

    private int updateCount(String sql, Object... args) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    private long insert(String sql, Object... args) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private void bind(PreparedStatement ps, Object... args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    private List<Map<String, Object>> rows(String sql, Object... args) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, args);
            ResultSet rs = ps.executeQuery();
            ResultSetMetaData md = rs.getMetaData();
            List<Map<String, Object>> list = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.put(md.getColumnLabel(i), rs.getObject(i));
                }
                list.add(row);
            }
            return list;
        }
    }

    private Map<String, Object> one(String sql, Object... args) throws Exception {
        List<Map<String, Object>> list = rows(sql, args);
        return list.isEmpty() ? null : list.get(0);
    }

    private int oneInt(String sql) throws Exception {
        Object value = one(sql).values().iterator().next();
        return value == null ? 0 : ((Number) value).intValue();
    }

    private BigDecimal money(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.replace(",", "."));
    }

    private String moneyText(BigDecimal value) {
        return BRL.format(value);
    }

    private void audit(String acao, String detalhe) throws Exception {
        update("insert into audit_log (usuario_id, acao, detalhe, timestamp) values (?, ?, ?, ?)",
                user == null ? null : user.id, acao, detalhe, LocalDateTime.now().toString());
        appLog("INFO", acao, detalhe, "");
    }

    private void appLog(String level, String context, String message, String details) {
        SupportLogger.log(level, context, message, details);
        try {
            update("insert into app_log (nivel, contexto, mensagem, detalhes, criado_em) values (?, ?, ?, ?, ?)",
                    level, context, message, details, LocalDateTime.now().toString());
        } catch (Exception ignored) {
            // File log already captured the event.
        }
    }

    private void msg(String text) {
        JOptionPane.showMessageDialog(frame, text);
    }

    private void error(Exception e) {
        e.printStackTrace();
        String actor = user == null ? "anonimo" : user.login + "/" + user.role;
        SupportLogger.logException("desktop", e, "usuario=" + actor);
        appLog("ERROR", "desktop", e.getClass().getSimpleName(), "usuario=" + actor + " | " + e.getMessage());
        JOptionPane.showMessageDialog(frame, friendlyMessage(e), "Erro", JOptionPane.ERROR_MESSAGE);
    }

    private String friendlyMessage(Exception e) {
        if (e instanceof AppException) {
            return e.getMessage();
        }
        if (e instanceof NumberFormatException) {
            return "Confira os campos numericos informados antes de continuar.";
        }
        if (e instanceof java.time.format.DateTimeParseException) {
            return "Confira a data informada. Use o formato AAAA-MM-DD.";
        }
        return "Ocorreu um erro inesperado. Consulte o log de suporte.";
    }

    private String first(org.w3c.dom.Document doc, String tag) {
        var list = doc.getElementsByTagName(tag);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent().trim();
    }

    private String text(Element element, String tag) {
        var list = element.getElementsByTagName(tag);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent().trim();
    }

    private String nextInternalCode() throws Exception {
        int nextId = safeInt("select coalesce(max(id),0) + 1 from produtos");
        return "P" + String.format("%05d", nextId);
    }

    private BigDecimal markupPrice(BigDecimal custo) {
        return custo.multiply(new BigDecimal("1.35")).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal weightedAverageCost(BigDecimal estoqueAtual, BigDecimal custoAtual, BigDecimal entrada, BigDecimal custoEntrada) {
        if (estoqueAtual.compareTo(BigDecimal.ZERO) <= 0) {
            return custoEntrada.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal totalQtd = estoqueAtual.add(entrada);
        return estoqueAtual.multiply(custoAtual)
                .add(entrada.multiply(custoEntrada))
                .divide(totalQtd, 2, RoundingMode.HALF_UP);
    }

    private void recordPriceHistory(Long produtoId, BigDecimal custoAnterior, BigDecimal custoNovo,
                                    BigDecimal vendaAnterior, BigDecimal vendaNova, String motivo) throws Exception {
        update("""
            insert into historico_preco
            (produto_id, preco_custo_anterior, preco_custo_novo, preco_venda_anterior, preco_venda_novo, alterado_por, motivo, timestamp)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """, produtoId, custoAnterior, custoNovo, vendaAnterior, vendaNova,
                user == null ? null : user.id, motivo, LocalDateTime.now().toString());
    }

    private int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private <T> T withTransaction(SqlSupplier<T> work) throws Exception {
        boolean autoCommit = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            T result = work.get();
            con.commit();
            return result;
        } catch (Exception e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(autoCommit);
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws Exception;
    }

    private record User(long id, String nome, String login, String role, BigDecimal descontoMaximo, boolean autorizaPrecoZero) {
        boolean admin() { return "ADMIN".equals(role) || "GERENTE".equals(role); }
    }

    private record Item(long id, String text) {
        @Override public String toString() { return text; }
    }

    private record CartItem(long produtoId, String nome, BigDecimal qtd, BigDecimal preco) {}
}
