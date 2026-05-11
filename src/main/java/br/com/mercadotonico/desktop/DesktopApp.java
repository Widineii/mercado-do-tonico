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
import javax.swing.table.TableRowSorter;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class DesktopApp {
    private static final NumberFormat BRL = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"));
    private static final DecimalFormat BR_NUMBER = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("pt-BR")));
    private static final DateTimeFormatter BR_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    // Paleta oficial Mercado do Tonico (estilo PDV verde popular).
    private static final Color MARKET_GREEN = new Color(0x1B, 0x5E, 0x20);        // verde principal #1b5e20
    private static final Color MARKET_GREEN_2 = new Color(0x2E, 0x7D, 0x32);      // verde secundario #2e7d32
    private static final Color MARKET_GREEN_SOFT = new Color(0xE8, 0xF5, 0xE9);   // verde claro #e8f5e9
    private static final Color MARKET_ORANGE = new Color(0xFF, 0xA7, 0x26);       // laranja acao #ffa726
    private static final Color MARKET_ORANGE_SOFT = new Color(0xFF, 0xF3, 0xE0);  // laranja claro #fff3e0
    private static final Color MARKET_RED = new Color(0xC6, 0x28, 0x28);          // vermelho destrutivo #c62828
    private static final Color MARKET_RED_SOFT = new Color(0xFF, 0xEB, 0xEE);     // vermelho claro #ffebee
    private static final Color MARKET_BG = new Color(0xF5, 0xF5, 0xF5);           // fundo #f5f5f5
    private static final Color PANEL_BG = Color.WHITE;
    private static final Color DARK_SURFACE = Color.WHITE;
    private static final Color DARK_SURFACE_2 = new Color(0xFA, 0xFA, 0xFA);
    private static final Color BORDER_SOFT = new Color(0xE0, 0xE0, 0xE0);         // borda sutil
    private static final Color BORDER_STRONG = new Color(0xCF, 0xD8, 0xDC);
    private static final Color TEXT_DARK = new Color(0x21, 0x21, 0x21);
    private static final Color TEXT_MUTED = new Color(0x61, 0x61, 0x61);
    private static final Color TEXT_ON_GREEN = new Color(0xF1, 0xF8, 0xE9);
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
    private JTabbedPane mainTabs;
    private DefaultTableModel cartModel;
    private JLabel totalLabel;
    private JComboBox<Item> clienteCombo;
    private JComboBox<Item> caixaCombo;
    private DesktopInventoryService inventoryService;
    private DesktopCashReportService cashReportService;
    private DesktopReturnService returnService;
    private DesktopFinanceService financeService;
    private DesktopReceiptService receiptService;
    private br.com.mercadotonico.integration.barcode.BarcodeLookupService barcodeLookupService;
    private Runnable paymentFeedbackUpdater = () -> {};
    /** Zerado em {@link #posPanel()}; pos-venda limpa pagamentos e atualiza totais sem recriar a janela. */
    private Runnable pdvAfterSaleCleanup = () -> {};
    /**
     * Recarrega as tabelas da aba "Convenio" sem reconstruir o frame.
     * Configurado em {@link #fiadoPanel()}; o PDV chama isso apos cada
     * venda no convenio (ou pagamento de convenio) pra que a divida
     * exibida fique sempre em dia. Default no-op para quando a aba ainda
     * nao foi montada.
     */
    private Runnable convenioRefresher = () -> {};
    /**
     * Atualiza os campos "Fundo" e "Cartao" no topo do PDV. Configurado em
     * {@link #posPanel()}; deve ser chamado apos cada venda concluida ou
     * sangria/suprimento para refletir o saldo de caixa em tempo real.
     */
    private Runnable pdvHeaderRefresher = () -> {};
    private final List<CartItem> cart = new ArrayList<>();
    private Integer pendingTabIndex;
    /** Indice da aba PDV em {@link #mainTabs}; -1 se o usuario nao tem PDV. */
    private int pdvTabIndex = -1;
    private JComponent pdvFormaPagamentoRoot;
    private JButton pdvFinalizarButton;
    /** Botao Limpar carrinho no PDV; usado pelo dispatcher global de F6 (combo/tabela consomem atalhos). */
    private JButton pdvLimparCarrinhoButton;
    private java.awt.KeyEventDispatcher pdvF4Dispatcher;

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
        barcodeLookupService = buildBarcodeLookupService();
    }

    private br.com.mercadotonico.integration.barcode.BarcodeLookupService buildBarcodeLookupService() {
        var service = new br.com.mercadotonico.integration.barcode.BarcodeLookupService(con);
        // Provider gratuito (sem chave) — sempre habilitado.
        service.addProvider(new br.com.mercadotonico.integration.barcode.OpenFoodFactsProvider());
        // Provider Cosmos Bluesoft (NCM/CEST) — opcional, requer X-Cosmos-Token.
        String cosmosToken = resolveCosmosToken();
        if (cosmosToken != null && !cosmosToken.isBlank()) {
            service.addProvider(new br.com.mercadotonico.integration.barcode.CosmosBluesoftProvider(cosmosToken));
        }
        return service;
    }

    private void openBarcodeLookupDialog() {
        try {
            requireInventoryAccess();
            BarcodeLookupDialog dialog = new BarcodeLookupDialog(
                    frame,
                    con,
                    barcodeLookupService,
                    inventoryService,
                    user.id,
                    seed -> {
                        try { return nextInternalCode(); }
                        catch (Exception ex) { return "AUTO-" + seed; }
                    },
                    () -> {
                        try {
                            audit("CADASTRO_BARCODE", "Produto cadastrado/atualizado via leitura de codigo de barras");
                        } catch (Exception ignored) {
                            // Auditoria nao deve quebrar o fluxo de cadastro.
                        }
                        refreshFrame();
                    }
            );
            dialog.setVisible(true);
        } catch (Exception ex) {
            error(ex);
        }
    }

    private String resolveCosmosToken() {
        String env = System.getenv("COSMOS_TOKEN");
        if (env != null && !env.isBlank()) return env.trim();
        File configFile = new File(DB_URL_CONFIG);
        if (configFile.exists()) {
            try (FileInputStream in = new FileInputStream(configFile)) {
                Properties props = new Properties();
                props.load(in);
                String t = props.getProperty("cosmos.token", "").trim();
                if (!t.isBlank()) return t;
            } catch (Exception ignored) {
                // Sem Cosmos token: o servico usara apenas o OpenFoodFacts.
            }
        }
        return null;
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
        JPasswordField senha = new JPasswordField();
        final char senhaEchoChar = senha.getEchoChar();
        senha.setEchoChar((char) 0);
        senha.setText("Senha");
        senha.setForeground(new Color(130, 130, 130));
        senha.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                String atual = new String(senha.getPassword());
                if ("Senha".equals(atual)) {
                    senha.setText("");
                    senha.setEchoChar(senhaEchoChar);
                    senha.setForeground(new Color(20, 20, 20));
                }
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                String atual = new String(senha.getPassword());
                if (atual.isBlank()) {
                    senha.setEchoChar((char) 0);
                    senha.setText("Senha");
                    senha.setForeground(new Color(130, 130, 130));
                }
            }
        });
        JPanel panel = formPanel();
        panel.add(label("Login")); panel.add(login);
        panel.add(label("Senha")); panel.add(senha);
        int option = JOptionPane.showConfirmDialog(null, panel, "Mercado do Tonico - Entrar", JOptionPane.OK_CANCEL_OPTION);
        if (option != JOptionPane.OK_OPTION) {
            return false;
        }
        String senhaInformada = new String(senha.getPassword());
        if ("Senha".equals(senhaInformada)) {
            senhaInformada = "";
        }
        try (PreparedStatement ps = con.prepareStatement("select * from usuarios where login=? and ativo=1")) {
            ps.setString(1, login.getText().trim());
            ResultSet rs = ps.executeQuery();
            if (rs.next() && encoder.matches(senhaInformada, rs.getString("senha_hash"))) {
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
        // Tamanho minimo: garante que o PDV ainda funcione mesmo se o usuario
        // restaurar a janela no canto da tela. Maior que antes para evitar
        // cortes de texto nos botoes do painel direito.
        if (ultraCompactMode()) {
            frame.setMinimumSize(new Dimension(960, 600));
        } else if (compactMode()) {
            frame.setMinimumSize(new Dimension(1100, 680));
        } else {
            frame.setMinimumSize(new Dimension(1200, 720));
        }
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        mainTabs = new JTabbedPane();
        mainTabs.setUI(new MtNavTabbedPaneUI());
        mainTabs.setFont(new Font("Segoe UI", Font.BOLD, fontSize(13)));
        mainTabs.setBackground(PANEL_BG);
        mainTabs.setOpaque(true);

        addNavTab("Painel", "\uD83C\uDFE0", tabWithAutoScroll(dashboardPanel()));
        if (UserPermissions.canAccessPdv(user.role)) {
            pdvTabIndex = mainTabs.getTabCount();
            addNavTab("PDV - Caixa", "\uD83D\uDED2", tabWithAutoScroll(posPanel()));
        } else {
            pdvTabIndex = -1;
        }
        if (UserPermissions.canAccessInventory(user.role)) {
            addNavTab("Estoque", "\uD83D\uDCE6", tabWithAutoScroll(estoquePanel()));
        }
        if (UserPermissions.canManageSuppliers(user.role)) {
            addNavTab("Fornecedores", "\uD83D\uDE9A", tabWithAutoScroll(fornecedoresPanel()));
        }
        if (UserPermissions.canImportXml(user.role)) {
            addNavTab("XML NF-e", "\uD83D\uDCC4", tabWithAutoScroll(xmlPanel()));
        }
        if (UserPermissions.canAccessFiado(user.role)) {
            addNavTab("Convênio", "\uD83D\uDCB3", tabWithAutoScroll(fiadoPanel()));
        }
        if (UserPermissions.canAccessFinance(user.role)) {
            addNavTab("Financeiro", "\uD83D\uDCB2", tabWithAutoScroll(financeiroPanel()));
        }
        if (UserPermissions.canAccessReports(user.role)) {
            addNavTab("Relatorios", "\uD83D\uDCCA", tabWithAutoScroll(relatoriosPanel()));
        }
        // Sincroniza estilo do tab component com a aba selecionada.
        Runnable refreshHeaders = () -> {
            for (int i = 0; i < mainTabs.getTabCount(); i++) {
                Component tc = mainTabs.getTabComponentAt(i);
                if (tc instanceof TabHeader th) {
                    th.setSelected(i == mainTabs.getSelectedIndex());
                }
            }
        };
        mainTabs.addChangeListener(e -> refreshHeaders.run());
        refreshHeaders.run();

        if (pendingTabIndex != null && pendingTabIndex >= 0) {
            mainTabs.setSelectedIndex(Math.min(pendingTabIndex, mainTabs.getTabCount() - 1));
            pendingTabIndex = null;
        }
        frame.setJMenuBar(menuBar());
        stripDefaultF10MenuTraversal(frame);
        installPdvF4ShortcutDispatcher(frame);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(MARKET_BG);
        root.add(appHeader(), BorderLayout.NORTH);
        root.add(mainTabs, BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        if (pdvTabIndex >= 0) {
            mainTabs.addChangeListener(e -> updatePdvDefaultButton());
            SwingUtilities.invokeLater(this::updatePdvDefaultButton);
        }
    }

    /** Adiciona uma aba com header custom (icone + texto). */
    private void addNavTab(String title, String emoji, Component content) {
        int idx = mainTabs.getTabCount();
        mainTabs.addTab(title, content);
        mainTabs.setTabComponentAt(idx, new TabHeader(emoji, title));
    }

    /** Header de aba: icone unicode (Segoe UI Emoji) + texto, com cor verde quando selecionada. */
    private final class TabHeader extends JPanel {
        private final JLabel iconLbl;
        private final JLabel textLbl;

        TabHeader(String emoji, String text) {
            setOpaque(false);
            setLayout(new FlowLayout(FlowLayout.LEFT, 6, 0));
            iconLbl = new JLabel(emoji == null ? "" : emoji);
            iconLbl.setFont(new Font("Segoe UI Emoji", Font.PLAIN, fontSize(13)));
            textLbl = new JLabel(text);
            textLbl.setFont(new Font("Segoe UI", Font.BOLD, fontSize(13)));
            add(iconLbl);
            add(textLbl);
            setBorder(new EmptyBorder(2, 4, 6, 4));
            setSelected(false);
        }

        void setSelected(boolean selected) {
            Color c = selected ? MARKET_GREEN : TEXT_MUTED;
            iconLbl.setForeground(c);
            textLbl.setForeground(c);
        }
    }

    /** UI custom para JTabbedPane: barra branca, aba ativa com texto verde + underline 3px verde. */
    private final class MtNavTabbedPaneUI extends javax.swing.plaf.basic.BasicTabbedPaneUI {
        @Override
        protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex,
                                          int x, int y, int w, int h, boolean isSelected) {
            g.setColor(PANEL_BG);
            g.fillRect(x, y, w, h);
            if (isSelected) {
                // Underline verde 3px no inferior da aba.
                g.setColor(MARKET_GREEN);
                g.fillRect(x + 4, y + h - 3, w - 8, 3);
            }
        }

        @Override
        protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex,
                                      int x, int y, int w, int h, boolean isSelected) {
            // sem borda nas abas
        }

        @Override
        protected void paintFocusIndicator(Graphics g, int tabPlacement, Rectangle[] rects,
                                           int tabIndex, Rectangle iconRect, Rectangle textRect,
                                           boolean isSelected) {
            // sem retangulo de foco
        }

        @Override
        protected void paintContentBorderTopEdge(Graphics g, int tabPlacement, int selectedIndex,
                                                 int x, int y, int w, int h) {
            // Linha cinza separando navbar do conteudo.
            g.setColor(BORDER_SOFT);
            g.fillRect(x, y, w, 1);
        }

        @Override
        protected void paintContentBorderBottomEdge(Graphics g, int tabPlacement, int selectedIndex,
                                                    int x, int y, int w, int h) { }
        @Override
        protected void paintContentBorderLeftEdge(Graphics g, int tabPlacement, int selectedIndex,
                                                  int x, int y, int w, int h) { }
        @Override
        protected void paintContentBorderRightEdge(Graphics g, int tabPlacement, int selectedIndex,
                                                   int x, int y, int w, int h) { }

        @Override
        protected Insets getContentBorderInsets(int tabPlacement) {
            return new Insets(1, 0, 0, 0);
        }

        @Override
        protected Insets getTabAreaInsets(int tabPlacement) {
            return new Insets(6, 12, 0, 12);
        }

        @Override
        protected Insets getTabInsets(int tabPlacement, int tabIndex) {
            return new Insets(6, 14, 6, 14);
        }

        @Override
        protected int calculateTabHeight(int tabPlacement, int tabIndex, int fontHeight) {
            return super.calculateTabHeight(tabPlacement, tabIndex, fontHeight) + 6;
        }
    }

    /**
     * Na aba PDV, o botao preto "Registrar venda" vira o default do frame (atalho Enter
     * em muitos campos). Fora do PDV, remove o default para nao conflitar com outras abas.
     */
    private void updatePdvDefaultButton() {
        if (frame == null) {
            return;
        }
        JRootPane rp = frame.getRootPane();
        if (rp == null) {
            return;
        }
        if (pdvTabIndex >= 0 && mainTabs != null
                && mainTabs.getSelectedIndex() == pdvTabIndex
                && pdvFinalizarButton != null) {
            rp.setDefaultButton(pdvFinalizarButton);
        } else {
            rp.setDefaultButton(null);
        }
    }

    /** Libera F10 para o atalho do PDV (cupom); o Swing usa F10 para focar o menu. */
    private static void stripDefaultF10MenuTraversal(JFrame frame) {
        KeyStroke f10 = KeyStroke.getKeyStroke(KeyEvent.VK_F10, 0);
        JRootPane rp = frame.getRootPane();
        if (rp != null) {
            rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).remove(f10);
            rp.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).remove(f10);
        }
        JMenuBar mb = frame.getJMenuBar();
        if (mb != null) {
            mb.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).remove(f10);
        }
    }

    /**
     * F4 no combo "Forma de pagamento" abre a lista no Windows — interceptamos para finalizar venda.
     * F6 nem sempre chega ao InputMap do painel (combo/tabela); aqui garantimos Limpar carrinho na aba PDV.
     */
    private void installPdvF4ShortcutDispatcher(JFrame frame) {
        if (pdvTabIndex < 0) {
            return;
        }
        pdvF4Dispatcher = this::interceptPdvShortcutDispatch;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(pdvF4Dispatcher);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (pdvF4Dispatcher != null) {
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(pdvF4Dispatcher);
                    pdvF4Dispatcher = null;
                }
            }
        });
    }

    private boolean interceptPdvShortcutDispatch(KeyEvent ev) {
        if (ev.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }
        if (pdvTabIndex < 0 || mainTabs == null || mainTabs.getSelectedIndex() != pdvTabIndex) {
            return false;
        }
        int code = ev.getKeyCode();
        if (code == KeyEvent.VK_F6 && pdvLimparCarrinhoButton != null) {
            pdvLimparCarrinhoButton.doClick();
            ev.consume();
            return true;
        }
        if (code != KeyEvent.VK_F4) {
            return false;
        }
        if (pdvFormaPagamentoRoot == null || pdvFinalizarButton == null) {
            return false;
        }
        Component fo = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
        if (fo == null || !SwingUtilities.isDescendingFrom(fo, pdvFormaPagamentoRoot)) {
            return false;
        }
        pdvFinalizarButton.doClick();
        ev.consume();
        return true;
    }

    private JComponent tabWithAutoScroll(JComponent content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        scroll.getHorizontalScrollBar().setUnitIncrement(20);
        return scroll;
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
        metrics.add(metricCard("Convênio em aberto", moneyText(safeMoney("select coalesce(sum(valor-valor_pago),0) from fiado where status='ABERTO'")), MARKET_GREEN));
        panel.add(metrics);
        panel.add(Box.createVerticalStrut(14));
        JPanel grid = new JPanel(new GridLayout(2, 2, 14, 14));
        grid.setOpaque(false);
        grid.add(section("Status dos caixas", table("select c.numero as Caixa, c.status as Status, coalesce(u.nome, '-') as Operador from caixas c left join usuarios u on u.id=c.operador_atual_id order by c.numero")));
        grid.add(section("Produtos abaixo do minimo", table("select nome as Produto, estoque_atual as Atual, estoque_minimo as Minimo from produtos where ativo=1 and estoque_atual <= estoque_minimo order by estoque_atual")));
        grid.add(section("Produtos vencendo em 30 dias", table("select nome as Produto, validade as Validade from produtos where ativo=1 and validade is not null and date(validade) <= date('now','+30 day') order by validade")));
        grid.add(section("Convênio em aberto", table("select c.nome as Cliente, sum(f.valor-f.valor_pago) as Aberto from fiado f join clientes c on c.id=f.cliente_id where f.status='ABERTO' group by c.id order by Aberto desc")));
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
        boolean modoOperador = "CAIXA".equalsIgnoreCase(user.role);
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(MARKET_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(scale(8), scale(8), scale(8), scale(8)));
        JPanel top = new JPanel(new BorderLayout(compactMode() ? 8 : 12, 0));
        top.setBackground(Color.WHITE);
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(219, 219, 226)),
                new EmptyBorder(2, 0, 2, 0)
        ));
        top.setBorder(new EmptyBorder(compactMode() ? 5 : 8, compactMode() ? 6 : 10, compactMode() ? 5 : 8, compactMode() ? 6 : 10));
        caixaCombo = combo("select id, 'Caixa ' || numero || ' - ' || status from caixas order by numero");
        caixaCombo.setFont(new Font("Segoe UI", Font.BOLD, 12));
        JTextField fundo = new JTextField("100,00");
        stylePdvField(fundo);
        bindMoneyMask(fundo);
        fundo.setColumns(7);
        JTextField cartaoTopo = new JTextField("0,00");
        stylePdvField(cartaoTopo);
        cartaoTopo.setColumns(7);
        cartaoTopo.setEditable(false);
        cartaoTopo.setEnabled(false);
        JButton abrir = button("Abrir caixa");
        abrir.addActionListener(e -> {
            try {
                Item caixaSel = (Item) caixaCombo.getSelectedItem();
                if (caixaSel == null) {
                    msg("Selecione um caixa.");
                    return;
                }
                Map<String, Object> caixaAtual = one("select status from caixas where id=?", caixaSel.id);
                boolean aberto = caixaAtual != null && "ABERTO".equals(String.valueOf(caixaAtual.get("status")));
                if (aberto) {
                    int confirmar = JOptionPane.showConfirmDialog(
                            frame,
                            "Voce deseja fechar o cx aberto: Caixa " + caixaSel.text + "?",
                            "Confirmar fechamento",
                            JOptionPane.YES_NO_OPTION
                    );
                    if (confirmar == JOptionPane.YES_OPTION) {
                        fecharCaixa();
                    }
                    return;
                }
                openCaixa(fundo.getText());
            } catch (Exception ex) {
                error(ex);
            }
        });
        JButton voltarMenu = button("Voltar menu");
        voltarMenu.addActionListener(e -> {
            if (mainTabs != null) {
                mainTabs.setSelectedIndex(0);
            }
        });
        // Barra unica que QUEBRA LINHA quando nao cabe (notebook ~1280px).
        // Antes era WEST/CENTER/EAST num BorderLayout, e o EAST cortava fora.
        JPanel topRow = new JPanel(new WrapLayout(FlowLayout.LEFT,
                compactMode() ? 4 : 8, compactMode() ? 4 : 6));
        topRow.setOpaque(false);
        JLabel vendaLabel = new JLabel("Venda");
        vendaLabel.setFont(new Font("Segoe UI", Font.BOLD, fontSize(compactMode() ? 17 : 22)));
        vendaLabel.setForeground(new Color(20, 20, 20));
        vendaLabel.setBorder(new EmptyBorder(0, 0, 0, compactMode() ? 6 : 10));
        topRow.add(vendaLabel);
        JPanel chipCodigo = shortcutChip("F1", "Codigo");
        JPanel chipFinalizar = shortcutChip("F4", "Registrar venda");
        JPanel chipLimpar = shortcutChip("F6", "Limpar carrinho");
        JPanel chipCancelar = shortcutChip("F7", "Cancelar item");
        JPanel chipCupom = shortcutChip("F10", "Cupom fiscal");
        JPanel chipDesconto = shortcutChip("F11", "Desconto item");
        JPanel chipEsc = shortcutChip("ESC", "Limpar campo");
        topRow.add(chipCodigo);
        topRow.add(chipFinalizar);
        topRow.add(chipLimpar);
        topRow.add(chipCancelar);
        topRow.add(chipCupom);
        topRow.add(chipDesconto);
        topRow.add(chipEsc);
        // separador visual entre atalhos e os campos do caixa
        JPanel sep = new JPanel();
        sep.setOpaque(false);
        sep.setPreferredSize(new Dimension(compactMode() ? 6 : 12, 1));
        topRow.add(sep);
        topRow.add(labelLight("Caixa"));
        topRow.add(caixaCombo);
        topRow.add(labelLight("Fundo"));
        topRow.add(fundo);
        topRow.add(labelLight("Cartao"));
        topRow.add(cartaoTopo);
        topRow.add(abrir);
        topRow.add(voltarMenu);
        top.add(topRow, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);

        cartModel = new DefaultTableModel(new Object[]{"Produto", "Qtd", "V. Unit", "Subtotal"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable cartTable = new JTable(cartModel);
        styleTable(cartTable);
        cartTable.setBackground(Color.WHITE);
        cartTable.setRowSelectionAllowed(true);
        cartTable.setColumnSelectionAllowed(false);
        cartTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // Envolvemos a tabela do carrinho num card "Itens da Venda" com header verde + icone.
        JPanel cartCard = section("Itens da Venda", cartTable);
        int sideWidth;
        if (ultraCompactMode()) {
            // Em notebook pequeno (1280x720), garantimos pelo menos 280px
            // para os textos "Adicionar produto" / "Finalizar Venda" caberem.
            sideWidth = Math.max(scale(280), Math.min(scale(330), (int) (screenSize.width * 0.27)));
        } else if (compactMode()) {
            sideWidth = Math.max(scale(290), Math.min(scale(360), (int) (screenSize.width * 0.25)));
        } else {
            sideWidth = Math.max(scale(320), Math.min(scale(440), (int) (screenSize.width * 0.28)));
        }

        // Largura fixa da coluna; altura vem do layout. Nunca usar altura 0 no preferredSize:
        // isso impede o JScrollPane de calcular a rolagem e corta botoes no fim do painel.
        final int pdvSideW = sideWidth;
        JPanel right = new JPanel() {
            @Override
            public Dimension getPreferredSize() {
                LayoutManager mgr = getLayout();
                if (mgr == null) {
                    return new Dimension(pdvSideW, super.getPreferredSize().height);
                }
                Dimension natural = mgr.preferredLayoutSize(this);
                int w = Math.max(pdvSideW, natural.width);
                return new Dimension(w, natural.height);
            }
        };
        right.setBackground(Color.WHITE);
        int pad = compactMode() ? 6 : 10;
        right.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(218, 218, 224)),
                new EmptyBorder(pad, pad, pad, pad)));
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        JTextField codigo = new JTextField();
        codigo.setFont(new Font("Segoe UI", Font.BOLD, fontSize(compactMode() ? 18 : 22)));
        codigo.setForeground(TEXT_DARK);
        codigo.setBackground(Color.WHITE);
        // Borda grossa verde (campo de codigo principal do caixa) com indicador de foco.
        javax.swing.border.Border codigoIdle = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT, 2),
                new EmptyBorder(compactMode() ? 8 : 12, compactMode() ? 10 : 14, compactMode() ? 8 : 12, compactMode() ? 10 : 14));
        javax.swing.border.Border codigoFocused = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(MARKET_GREEN, 2),
                new EmptyBorder(compactMode() ? 8 : 12, compactMode() ? 10 : 14, compactMode() ? 8 : 12, compactMode() ? 10 : 14));
        codigo.setBorder(codigoIdle);
        codigo.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { codigo.setBorder(codigoFocused); }
            @Override public void focusLost(java.awt.event.FocusEvent e) { codigo.setBorder(codigoIdle); }
        });
        codigo.setMaximumSize(new Dimension(Integer.MAX_VALUE, compactMode() ? 40 : 52));
        DefaultListModel<String> sugestoesModel = new DefaultListModel<>();
        JList<String> sugestoesList = new JList<>(sugestoesModel);
        sugestoesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sugestoesList.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(13)));
        // FIXED cell height - sem isso o getFixedCellHeight retorna -1
        // e a conta da altura do scroll fica errada.
        sugestoesList.setFixedCellHeight(compactMode() ? 22 : 26);
        sugestoesList.setVisibleRowCount(8);
        // CRITICO: lista NAO focavel - navegacao e feita via shortcuts no
        // textfield "codigo". Sem isso o caret pisca/sai do campo.
        sugestoesList.setFocusable(false);
        sugestoesList.setRequestFocusEnabled(false);
        JScrollPane sugestoesScroll = new JScrollPane(sugestoesList);
        sugestoesScroll.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 208)));
        // ALINHAMENTO: o painel direito (BoxLayout.Y_AXIS) usa alignmentX
        // dos filhos pra alinhar/esticar. Sem isso, JScrollPane (default
        // CENTER 0.5f) some/encolhe quando posto entre componentes que
        // alinham a esquerda - parece "lista vazia" mesmo tendo itens.
        sugestoesScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        // A lista de sugestoes do produto fica EMBUTIDA no painel direito
        // do PDV (logo abaixo do textfield "codigo"). Antes era JPopupMenu,
        // que intercepta teclado via MenuSelectionManager e impedia o
        // operador de continuar digitando depois que o popup abria.
        // Embutida, o foco fica preso no textfield e o usuario digita
        // continuamente. Inicialmente invisivel - so aparece quando
        // ha sugestoes pra mostrar.
        sugestoesScroll.setVisible(false);
        final java.util.List<Map<String, Object>> sugestoesProdutos = new ArrayList<>();
        final boolean[] escolhendoSugestao = {false};
        final java.util.List<Map<String, Object>> catalogoSugestoes = new ArrayList<>();
        try {
            catalogoSugestoes.addAll(carregarCatalogoSugestoes());
        } catch (Exception ignored) {
            // Se falhar em carregar catalogo, segue sem sugestoes.
        }
        // Quantidade comeca em "0" - quando o operador clica/foca o campo
        // o conteudo e selecionado todo, entao a primeira tecla numerica
        // SUBSTITUI o zero (digitar "2" -> "2", nao "02").
        JTextField qtd = new JTextField("0");
        stylePdvField(qtd);
        qtd.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent ev) {
                SwingUtilities.invokeLater(qtd::selectAll);
            }
        });
        // MouseListener tambem - em alguns LAFs o focusGained nao seleciona
        // direito quando o campo ja esta focado e o usuario so clica.
        qtd.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent ev) {
                if ("0".equals(qtd.getText())) qtd.selectAll();
            }
        });
        JButton add = button("Adicionar produto");
        add.addActionListener(e -> {
            addCart(codigo.getText(), qtd.getText());
            codigo.setText("");
            sugestoesScroll.setVisible(false);
            qtd.setText("0");
            codigo.requestFocus();
        });
        // TOTAL GIGANTE em verde no card da direita (foto: "TOTAL DA COMPRA").
        totalLabel = new JLabel("R$ 0,00");
        totalLabel.setHorizontalAlignment(SwingConstants.CENTER);
        totalLabel.setOpaque(true);
        totalLabel.setBackground(MARKET_GREEN_SOFT);
        totalLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(blend(MARKET_GREEN, Color.WHITE, 0.65f), 1),
                new EmptyBorder(compactMode() ? 14 : 20, compactMode() ? 8 : 12, compactMode() ? 14 : 20, compactMode() ? 8 : 12)));
        totalLabel.setFont(new Font("Segoe UI", Font.BOLD, fontSize(compactMode() ? 32 : 44)));
        totalLabel.setForeground(MARKET_GREEN);
        clienteCombo = combo("select id, nome from clientes where bloqueado = 0 order by nome");
        clienteCombo.setSelectedIndex(-1);
        JTextField dinheiro = new JTextField("0,00");
        JTextField debito = new JTextField("0,00");
        JTextField credito = new JTextField("0,00");
        JTextField pix = new JTextField("0,00");
        JTextField fiado = new JTextField("0,00");
        JTextField creditoTroca = new JTextField("0,00");
        stylePdvField(dinheiro);
        stylePdvField(debito);
        stylePdvField(credito);
        stylePdvField(pix);
        stylePdvField(fiado);
        stylePdvField(creditoTroca);
        bindMoneyMask(dinheiro);
        bindMoneyMask(debito);
        bindMoneyMask(credito);
        bindMoneyMask(pix);
        bindMoneyMask(fiado);
        bindMoneyMask(creditoTroca);
        JComboBox<String> formaPagamento = new JComboBox<>(new String[]{"DINHEIRO", "PIX", "DEBITO", "CREDITO", "FIADO"});
        formaPagamento.setFont(new Font("Segoe UI", Font.BOLD, fontSize(12)));
        formaPagamento.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value != null) {
                    setText(PaymentAllocationService.paymentDisplayName(value.toString()));
                }
                return this;
            }
        });

        // -------------------------------------------------------------
        // Busca inteligente do cliente do convenio (autocomplete).
        // -------------------------------------------------------------
        // Aparece SOMENTE quando a forma de pagamento e "FIADO" (Convenio).
        // O operador digita o inicio do nome do cliente (ex: "g") e o
        // sistema mostra abaixo um popup com os clientes ativos cujo nome
        // comeca com o que foi digitado (case insensitive). Pode continuar
        // digitando para refinar, ou usar setas + Enter / clique para
        // selecionar. Tambem aceita CPF e telefone como prefixo, ja que
        // muitas vezes o cliente e identificado por CPF.
        JLabel lblClienteConvenio = label("Cliente do convênio (digite o nome ou CPF)");
        JTextField clienteBusca = new JTextField();
        stylePdvField(clienteBusca);
        clienteBusca.setToolTipText("Digite o inicio do nome (ex: 'g' lista todos os clientes que comecam com G). Use setas para escolher e Enter para confirmar.");
        DefaultListModel<Item> sugestoesClienteModel = new DefaultListModel<>();
        JList<Item> sugestoesClienteList = new JList<>(sugestoesClienteModel);
        sugestoesClienteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sugestoesClienteList.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(13)));
        sugestoesClienteList.setFixedCellHeight(compactMode() ? 24 : 28);
        sugestoesClienteList.setVisibleRowCount(8);
        // CRITICO: a lista NAO e focavel. Toda navegacao (Up/Down/Enter)
        // e tratada via KeyListener no textfield "clienteBusca", para que
        // o popup nao roube foco do campo de digitacao - evitando o
        // efeito de "pisca e some" do popup.
        sugestoesClienteList.setFocusable(false);
        JScrollPane sugestoesClienteScroll = new JScrollPane(sugestoesClienteList);
        sugestoesClienteScroll.setBorder(BorderFactory.createLineBorder(MARKET_GREEN, 1));
        // ALINHAMENTO LEFT pra casar com os outros componentes do painel
        // direito (BoxLayout.Y_AXIS). Sem isso o JScrollPane (default
        // CENTER 0.5f) some/encolhe entre os outros campos.
        sugestoesClienteScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        // CRITICO: a lista de sugestoes vai EMBUTIDA no painel direito do
        // PDV, logo abaixo do textfield. Nao usamos JPopupMenu nem JWindow:
        // ambos causam problemas de foco no Windows (JPopupMenu intercepta
        // teclado via MenuSelectionManager; JWindow as vezes rouba foco
        // mesmo com setFocusableWindowState(false)). Como a lista vive no
        // mesmo container do textfield e e setFocusable(false), o caret
        // nunca sai - o operador pode digitar continuamente.
        sugestoesClienteList.setRequestFocusEnabled(false);
        sugestoesClienteScroll.setVisible(false);

        JTextField valorRecebido = new JTextField("0,00");
        stylePdvField(valorRecebido);
        bindMoneyMask(valorRecebido);
        DefaultTableModel pagamentosModel = new DefaultTableModel(new Object[]{"Forma de pagamento", "Valor recebido"}, 0);
        JTable pagamentosTable = new JTable(pagamentosModel);
        styleTable(pagamentosTable);
        pagamentosTable.setRowHeight(compactMode() ? 22 : 26);
        JScrollPane pagamentosScroll = new JScrollPane(pagamentosTable);
        pagamentosScroll.setPreferredSize(new Dimension(0, compactMode() ? 92 : 120));
        pagamentosScroll.setBorder(BorderFactory.createLineBorder(new Color(215, 215, 215)));
        JButton adicionarPagamento = button("Adicionar pagamento");
        JButton removerPagamento = button("Remover selecionado");
        JButton confirmarPagamento = button("Confirmar");
        JLabel resumoSubtotal = new JLabel("Subtotal: R$ 0,00");
        JLabel resumoDescontosLinha = new JLabel("Desconto no carrinho: R$ 0,00");
        JLabel resumoTotal = new JLabel("Valor total: R$ 0,00");
        JLabel resumoTroco = new JLabel("Troco para o cliente: R$ 0,00");
        stylePdvResumoLinha(resumoSubtotal, false);
        stylePdvResumoLinha(resumoDescontosLinha, false);
        stylePdvResumoLinha(resumoTotal, true);
        stylePdvResumoLinha(resumoTroco, true);
        Map<String, BigDecimal> pagamentosDigitados = new LinkedHashMap<>();
        final BigDecimal[] dinheiroRecebidoInformado = {BigDecimal.ZERO};
        // Holder do "auto-pick" do cliente do convenio. E preenchido mais
        // abaixo (no setup do autocomplete) e chamado tanto pelo Finalizar
        // Venda quanto pelo Adicionar pagamento. Ele pega a sugestao
        // destacada no popup quando o operador apertou Finalizar sem ter
        // dado Enter na sugestao - cenario comum no caixa.
        final Runnable[] autoPickClienteConvenio = new Runnable[]{ () -> {} };
        // Botao laranja "Finalizar Venda" (igual a foto). styledButton ja garante
        // hover/pressed e cantos arredondados; apenas aumentamos a fonte e altura.
        JButton finalizar = styledButton("\uD83D\uDCB2  Finalizar Venda", MARKET_ORANGE);
        finalizar.setFont(new Font("Segoe UI Emoji", Font.BOLD, fontSize(compactMode() ? 16 : 19)));
        finalizar.setPreferredSize(new Dimension(0, compactMode() ? 50 : 64));
        finalizar.setMaximumSize(new Dimension(Integer.MAX_VALUE, compactMode() ? 50 : 64));
        finalizar.setAlignmentX(Component.LEFT_ALIGNMENT);
        finalizar.addActionListener(e -> {
            try {
                String forma = Objects.requireNonNull(formaPagamento.getSelectedItem()).toString();
                BigDecimal total = cartTotalLiquido().max(BigDecimal.ZERO);
                BigDecimal valorInformado = money(valorRecebido.getText());
                BigDecimal valorAplicado;
                dinheiroRecebidoInformado[0] = BigDecimal.ZERO;

                if ("FIADO".equals(forma)) {
                    // Se ha sugestao destacada no popup mas o operador
                    // nao deu Enter, confirma automaticamente antes de
                    // pedir cliente (evita o "Selecione um cliente..." em
                    // tela quando o cliente ja aparecia destacado).
                    autoPickClienteConvenio[0].run();
                    if (clienteCombo.getSelectedItem() == null) {
                        // Tenta primeiro o que estiver no campo de busca inteligente.
                        Item cliFiado = selecionarClienteFiado(clienteBusca.getText());
                        if (cliFiado == null) {
                            msg("Selecione um cliente para venda no convênio.");
                            return;
                        }
                        // setSelectedItem(cliFiado) so funciona se o Item
                        // (record) estiver IDENTICO no combo. Como o combo
                        // pode ter sido construido antes do cliente existir
                        // (ou com texto diferente), procuramos por ID e,
                        // se nao achar, adicionamos. Sem isso o combo fica
                        // null e finalizarVenda reclama "selecione cliente".
                        boolean encontrado = false;
                        for (int i = 0; i < clienteCombo.getItemCount(); i++) {
                            Item it = clienteCombo.getItemAt(i);
                            if (it != null && it.id() == cliFiado.id()) {
                                clienteCombo.setSelectedIndex(i);
                                encontrado = true;
                                break;
                            }
                        }
                        if (!encontrado) {
                            clienteCombo.addItem(cliFiado);
                            clienteCombo.setSelectedItem(cliFiado);
                        }
                        clienteBusca.setText(cliFiado.text());
                    }
                }
                if ("PIX".equals(forma) || "DEBITO".equals(forma) || "CREDITO".equals(forma) || "FIADO".equals(forma)) {
                    valorAplicado = total;
                } else {
                    BusinessRules.requirePositive(valorInformado, "Valor recebido");
                    valorAplicado = valorInformado.min(total);
                    dinheiroRecebidoInformado[0] = valorInformado;
                }
                BusinessRules.requirePositive(valorAplicado, "Valor recebido");
                pagamentosDigitados.clear();
                pagamentosDigitados.put(forma, valorAplicado);
                dinheiro.setText(pagamentosDigitados.getOrDefault("DINHEIRO", BigDecimal.ZERO).toPlainString());
                debito.setText(pagamentosDigitados.getOrDefault("DEBITO", BigDecimal.ZERO).toPlainString());
                credito.setText(pagamentosDigitados.getOrDefault("CREDITO", BigDecimal.ZERO).toPlainString());
                pix.setText(pagamentosDigitados.getOrDefault("PIX", BigDecimal.ZERO).toPlainString());
                fiado.setText(pagamentosDigitados.getOrDefault("FIADO", BigDecimal.ZERO).toPlainString());
                creditoTroca.setText(BigDecimal.ZERO.toPlainString());

                finalizarVenda(
                        paymentInputs(dinheiro, debito, credito, pix, fiado, creditoTroca),
                        ""
                );
            } catch (Exception ex) {
                error(ex);
            }
        });
        JButton limpar = button("Limpar carrinho");
        pdvLimparCarrinhoButton = limpar;
        limpar.addActionListener(e -> { cart.clear(); refreshCart(); });
        JButton cancelarItem = button("Cancelar item");
        cancelarItem.addActionListener(e -> cancelarItemSelecionado(cartTable));
        JButton comprovante = button("Reemitir cupom fiscal");
        comprovante.addActionListener(e -> reemitirComprovante());
        if (modoOperador) {
            add.setFont(new Font("Segoe UI", Font.BOLD, fontSize(compactMode() ? 13 : 15)));
            finalizar.setFont(new Font("Segoe UI Emoji", Font.BOLD, fontSize(compactMode() ? 17 : 20)));
            add.setPreferredSize(new Dimension(0, compactMode() ? 38 : 48));
            finalizar.setPreferredSize(new Dimension(0, compactMode() ? 52 : 68));
            finalizar.setMaximumSize(new Dimension(Integer.MAX_VALUE, compactMode() ? 52 : 68));
        }
        finalizar.setToolTipText("Registrar venda (F4). Grava a venda, baixa estoque e aparece no relatorio.");

        // Botao Cancelar Venda (vermelho) - sempre disponivel ao lado do Finalizar.
        JButton cancelarVendaBtn = styledButton("\u274C  Cancelar Venda", MARKET_RED);
        cancelarVendaBtn.setFont(new Font("Segoe UI Emoji", Font.BOLD, fontSize(compactMode() ? 13 : 15)));
        cancelarVendaBtn.setPreferredSize(new Dimension(0, compactMode() ? 40 : 48));
        cancelarVendaBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, compactMode() ? 40 : 48));
        cancelarVendaBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        cancelarVendaBtn.setToolTipText("Limpa carrinho e pagamentos digitados (sem registrar venda).");
        cancelarVendaBtn.addActionListener(e -> {
            if (cart.isEmpty() && pagamentosDigitados.isEmpty()) {
                return;
            }
            int op = JOptionPane.showConfirmDialog(frame,
                    "Tem certeza que quer cancelar a venda? Itens e pagamentos serao descartados.",
                    "Cancelar Venda", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (op != JOptionPane.YES_OPTION) return;
            cart.clear();
            pagamentosDigitados.clear();
            dinheiroRecebidoInformado[0] = BigDecimal.ZERO;
            refreshCart();
            paymentFeedbackUpdater.run();
        });

        right.add(sectionTitlePdv("\uD83D\uDED2  Adicionar Item"));
        right.add(label("Codigo / Descricao")); right.add(codigo);
        // Lista de sugestoes (autocomplete) foi removida do layout do PDV
        // a pedido do operador - estava ficando muito grande e atrapalhando
        // a tela. O componente continua existindo no codigo (pra nao
        // quebrar listeners ja registrados), mas e mantido invisivel.
        sugestoesScroll.setVisible(false);
        right.add(Box.createVerticalStrut(4));
        right.add(label("Quantidade")); right.add(qtd);
        right.add(Box.createVerticalStrut(4));
        right.add(add);
        // Botao "Pesquisar produto": abre uma janela com TODOS os produtos
        // ativos cadastrados, com busca em tempo real por nome / codigo de
        // barras / SKU. Util quando o cliente nao trouxe etiqueta ou
        // quando o operador nao lembra o codigo. Para venda rapida com
        // leitor de codigo de barras, basta escanear no campo "Codigo /
        // Descricao" - se o produto ja estiver no carrinho, a quantidade
        // e somada automaticamente (mesmo produtoId) sem abrir janela.
        right.add(Box.createVerticalStrut(compactMode() ? 4 : 6));
        JButton pesquisarProduto = button("\uD83D\uDD0D  Pesquisar produto");
        pesquisarProduto.setToolTipText("Abre uma janela com todos os produtos. Escolha um item: ele preenche Codigo / Descricao; ajuste Quantidade e clique Adicionar produto.");
        pesquisarProduto.addActionListener(e -> abrirPesquisaProduto(codigo));
        right.add(pesquisarProduto);
        right.add(Box.createVerticalStrut(compactMode() ? 8 : 14));
        right.add(sectionTitlePdv("\uD83D\uDCB0  TOTAL DA COMPRA"));
        right.add(totalLabel);
        right.add(Box.createVerticalStrut(compactMode() ? 6 : 12));
        right.add(sectionTitlePdv("\uD83D\uDCB3  Forma de Pagamento"));
        right.add(label("Forma de pagamento")); right.add(formaPagamento);
        // Antes mostravamos aqui um campo de busca inteligente (clienteBusca
        // + lista de sugestoes) quando "Convenio" era selecionado. A
        // selecao do cliente foi movida toda para o dialogo que ja
        // aparece quando o operador clica em "Finalizar Venda" com a
        // forma "Convenio" (selecionarClienteFiado). Assim a tela do
        // PDV fica mais limpa e o fluxo nao fica duplicado.
        // Os componentes lblClienteConvenio / clienteBusca /
        // sugestoesClienteScroll continuam existindo no codigo (pra nao
        // quebrar listeners ja registrados) mas NAO entram no layout.
        lblClienteConvenio.setVisible(false);
        clienteBusca.setVisible(false);
        sugestoesClienteScroll.setVisible(false);
        JLabel lblValorRecebido = label("Valor recebido");
        right.add(lblValorRecebido);
        right.add(valorRecebido);
        right.add(resumoSubtotal);
        right.add(resumoDescontosLinha);
        right.add(resumoTotal);
        right.add(resumoTroco);
        right.add(finalizar);
        right.add(Box.createVerticalStrut(compactMode() ? 4 : 6));
        right.add(cancelarVendaBtn);
        right.add(Box.createVerticalStrut(compactMode() ? 8 : 10));
        int actionGap = compactMode() ? 6 : 8;
        JPanel actions = new JPanel(new GridLayout(3, 1, actionGap, actionGap));
        actions.setOpaque(false);
        actions.add(limpar);
        actions.add(cancelarItem);
        actions.add(comprovante);
        right.add(actions);
        Runnable sugerirValorRecebido = () -> {
            BigDecimal total = cartTotalLiquido().max(BigDecimal.ZERO);
            BigDecimal recebidoAplicado = pagamentosDigitados.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal faltante = total.subtract(recebidoAplicado).max(BigDecimal.ZERO);
            valorRecebido.setText(moneyInputText(faltante));
            valorRecebido.selectAll();
        };
        Runnable atualizarModoValorRecebido = () -> {
            String forma = Objects.requireNonNull(formaPagamento.getSelectedItem()).toString();
            boolean formaDinheiro = "DINHEIRO".equals(forma);
            valorRecebido.setEditable(formaDinheiro);
            valorRecebido.setEnabled(formaDinheiro);
            lblValorRecebido.setVisible(formaDinheiro);
            valorRecebido.setVisible(formaDinheiro);
            resumoTroco.setVisible(formaDinheiro);
            // Para "Convenio" nao mostramos campo de busca aqui no painel:
            // a selecao do cliente acontece no dialogo aberto ao clicar
            // em "Finalizar Venda" (selecionarClienteFiado). Mantemos
            // os componentes invisiveis e o textfield limpo.
            lblClienteConvenio.setVisible(false);
            clienteBusca.setVisible(false);
            sugestoesClienteScroll.setVisible(false);
            if (!"FIADO".equals(forma)) {
                clienteBusca.setText("");
            }
            sugerirValorRecebido.run();
        };

        // -------------------------------------------------------------
        // Comportamento da busca inteligente do cliente do convenio.
        // -------------------------------------------------------------
        // Recarrega o popup com base no texto atual do campo. Usa LIKE
        // com prefixo no nome (case insensitive) e tambem aceita prefixo
        // de CPF/telefone — facilita a vida do operador.
        Runnable atualizarSugestoesCliente = () -> {
            String termo = clienteBusca.getText() == null ? "" : clienteBusca.getText().trim();
            sugestoesClienteModel.clear();
            if (termo.isEmpty()) {
                sugestoesClienteScroll.setVisible(false);
                return;
            }
            try {
                String like = termo.toLowerCase(Locale.ROOT) + "%";
                List<Map<String, Object>> linhas = rows("""
                        select id, nome, coalesce(cpf,'-') as cpf, coalesce(telefone,'-') as telefone
                          from clientes
                         where bloqueado = 0
                           and (lower(nome) like ?
                                or replace(coalesce(cpf,''), '.', '') like ?
                                or replace(coalesce(telefone,''), ' ', '') like ?)
                         order by nome
                         limit 12
                        """, like, termo + "%", termo + "%");
                for (Map<String, Object> linha : linhas) {
                    long id = ((Number) linha.get("id")).longValue();
                    String nome = String.valueOf(linha.get("nome"));
                    String cpf = String.valueOf(linha.get("cpf"));
                    sugestoesClienteModel.addElement(new Item(id, nome + "  -  CPF " + cpf));
                }
            } catch (Exception ignored) {
                // Falha de query nao deve quebrar o PDV.
            }
            if (sugestoesClienteModel.isEmpty()) {
                sugestoesClienteScroll.setVisible(false);
                return;
            }
            sugestoesClienteList.setSelectedIndex(0);
            sugestoesClienteList.ensureIndexIsVisible(0);
            // Ajusta a altura do scroll embutido conforme a quantidade de
            // resultados (no maximo 6 linhas visiveis - se tiver mais, o
            // operador rola).
            int linhasVisiveis = Math.min(sugestoesClienteModel.size(), 6);
            int alturaCelula = sugestoesClienteList.getFixedCellHeight();
            int alturaScroll = alturaCelula * linhasVisiveis + 8;
            // Short.MAX_VALUE na largura: BoxLayout estica o scroll. Se
            // usarmos 0, o BoxLayout colapsa pra zero e a lista some.
            sugestoesClienteScroll.setPreferredSize(new Dimension(Short.MAX_VALUE, alturaScroll));
            sugestoesClienteScroll.setMaximumSize(new Dimension(Short.MAX_VALUE, alturaScroll));
            sugestoesClienteScroll.setMinimumSize(new Dimension(0, alturaScroll));
            sugestoesClienteScroll.setVisible(true);
            java.awt.Container parent = sugestoesClienteScroll.getParent();
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }
        };

        // Selecionar o item destacado no popup. Atualiza o clienteCombo
        // (fonte de verdade usada por finalizarVenda) e o textfield com o
        // nome puro (sem o "  -  CPF ...") pro operador ver claramente.
        Runnable confirmarClienteSelecionado = () -> {
            int idx = sugestoesClienteList.getSelectedIndex();
            if (idx < 0 && !sugestoesClienteModel.isEmpty()) {
                idx = 0;
            }
            if (idx < 0) return;
            Item escolhido = sugestoesClienteModel.get(idx);
            // Recupera o nome puro do banco pra colocar de volta no clienteCombo
            // (o combo guarda nome sem o sufixo "  -  CPF ...").
            String nomePuro = escolhido.text();
            int separadorCpf = nomePuro.indexOf("  -  CPF ");
            if (separadorCpf >= 0) nomePuro = nomePuro.substring(0, separadorCpf);
            Item paraCombo = new Item(escolhido.id(), nomePuro);
            // Garante que o item exista no combo (recarrega se for novo).
            boolean encontrado = false;
            for (int i = 0; i < clienteCombo.getItemCount(); i++) {
                Item it = clienteCombo.getItemAt(i);
                if (it != null && it.id() == paraCombo.id()) {
                    clienteCombo.setSelectedIndex(i);
                    encontrado = true;
                    break;
                }
            }
            if (!encontrado) {
                clienteCombo.addItem(paraCombo);
                clienteCombo.setSelectedItem(paraCombo);
            }
            clienteBusca.setText(nomePuro);
            sugestoesClienteScroll.setVisible(false);
        };

        // Plugamos o auto-pick: quando o operador clica "Finalizar venda"
        // (ou Adicionar pagamento) sem ter dado Enter na sugestao, esta
        // funcao confirma a sugestao destacada no popup automaticamente.
        // Se nao houver popup ativo, nao faz nada.
        autoPickClienteConvenio[0] = () -> {
            if (clienteCombo.getSelectedItem() != null) return;
            if (sugestoesClienteModel.isEmpty()) return;
            confirmarClienteSelecionado.run();
        };

        // Listener no texto: a cada digitacao, refaz a busca. Tambem zera
        // o cliente selecionado pra forcar o operador a escolher de novo
        // se estiver mexendo no nome.
        // DocumentListener da busca inteligente do convenio foi DESABILITADO
        // porque o campo de busca embutido foi removido do layout do PDV.
        // Antes, esse listener disparava clienteCombo.setSelectedIndex(-1)
        // a cada digitacao, mas como agora chamamos clienteBusca.setText()
        // programaticamente apos selecionar pelo dialogo, isso anulava a
        // selecao do cliente e fazia "Selecione um cliente..." aparecer.

        // Setas / Enter / Esc no campo de busca: navega no popup, confirma
        // ou fecha. Down -> entra no popup; Enter -> confirma a selecao;
        // Esc -> fecha o popup.
        clienteBusca.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent ev) {
                if (sugestoesClienteModel.isEmpty()) return;
                int code = ev.getKeyCode();
                if (code == KeyEvent.VK_DOWN) {
                    int next = Math.min(sugestoesClienteList.getSelectedIndex() + 1,
                            sugestoesClienteModel.size() - 1);
                    sugestoesClienteList.setSelectedIndex(Math.max(0, next));
                    sugestoesClienteList.ensureIndexIsVisible(Math.max(0, next));
                    ev.consume();
                } else if (code == KeyEvent.VK_UP) {
                    int prev = Math.max(0, sugestoesClienteList.getSelectedIndex() - 1);
                    sugestoesClienteList.setSelectedIndex(prev);
                    sugestoesClienteList.ensureIndexIsVisible(prev);
                    ev.consume();
                } else if (code == KeyEvent.VK_ENTER) {
                    confirmarClienteSelecionado.run();
                    ev.consume();
                } else if (code == KeyEvent.VK_ESCAPE) {
                    sugestoesClienteScroll.setVisible(false);
                    ev.consume();
                }
            }
        });

        // Clique simples ja seleciona (UX comum em autocomplete).
        sugestoesClienteList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent ev) {
                int idx = sugestoesClienteList.locationToIndex(ev.getPoint());
                if (idx >= 0) {
                    sugestoesClienteList.setSelectedIndex(idx);
                    confirmarClienteSelecionado.run();
                }
            }
        });

        // Como o scroll de sugestoes esta embutido no painel direito (sem
        // popup/JWindow), ele se mantem na hierarquia e nao precisa de
        // listeners de foco/movimento pra esconder. Quando o operador
        // muda de forma de pagamento, sai do PDV ou finaliza a venda, o
        // proprio fluxo ja chama setVisible(false) no scroll.
        Runnable paymentSummaryUpdater = () -> {
            BigDecimal bruto = cartSubtotalBrutoProdutos();
            BigDecimal descItens = cartDescontosAcumulados();
            BigDecimal total = cartTotalLiquido().max(BigDecimal.ZERO);
            BigDecimal recebidoAplicado = pagamentosDigitados.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal dinheiroAplicado = pagamentosDigitados.getOrDefault("DINHEIRO", BigDecimal.ZERO);
            BigDecimal dinheiroVisual = dinheiroRecebidoInformado[0].max(dinheiroAplicado);
            BigDecimal recebidoVisual = recebidoAplicado.subtract(dinheiroAplicado).add(dinheiroVisual);
            BigDecimal diferenca = total.subtract(recebidoVisual);

            dinheiro.setText(pagamentosDigitados.getOrDefault("DINHEIRO", BigDecimal.ZERO).toPlainString());
            debito.setText(pagamentosDigitados.getOrDefault("DEBITO", BigDecimal.ZERO).toPlainString());
            credito.setText(pagamentosDigitados.getOrDefault("CREDITO", BigDecimal.ZERO).toPlainString());
            pix.setText(pagamentosDigitados.getOrDefault("PIX", BigDecimal.ZERO).toPlainString());
            fiado.setText(pagamentosDigitados.getOrDefault("FIADO", BigDecimal.ZERO).toPlainString());
            creditoTroca.setText(pagamentosDigitados.getOrDefault("CREDITO_TROCA", BigDecimal.ZERO).toPlainString());

            resumoSubtotal.setText("Subtotal: " + moneyText(bruto.max(BigDecimal.ZERO)));
            resumoDescontosLinha.setText("Desconto no carrinho: " + moneyText(descItens.max(BigDecimal.ZERO)));
            resumoTotal.setText("Valor total: " + moneyText(total.max(BigDecimal.ZERO)));
            if (resumoTroco.isVisible()) {
                resumoTroco.setText("Troco para o cliente: " + moneyText(diferenca.compareTo(BigDecimal.ZERO) < 0 ? diferenca.abs() : BigDecimal.ZERO));
            }
            if (valorRecebido.isVisible() && !valorRecebido.isFocusOwner()) {
                sugerirValorRecebido.run();
            }
        };
        Runnable recarregarTabelaPagamentos = () -> {
            pagamentosModel.setRowCount(0);
            for (Map.Entry<String, BigDecimal> e : pagamentosDigitados.entrySet()) {
                pagamentosModel.addRow(new Object[]{
                        PaymentAllocationService.paymentDisplayName(e.getKey()),
                        moneyText(e.getValue())});
            }
            paymentSummaryUpdater.run();
        };
        Runnable addPagamentoAction = () -> {
            try {
                String forma = Objects.requireNonNull(formaPagamento.getSelectedItem()).toString();
                if ("FIADO".equals(forma)) {
                    // Mesma regra do "Finalizar venda": se o operador
                    // tem sugestao destacada no popup, usamos.
                    autoPickClienteConvenio[0].run();
                    if (clienteCombo.getSelectedItem() == null) {
                        Item clienteSelecionado = selecionarClienteFiado(clienteBusca.getText());
                        if (clienteSelecionado == null) {
                            msg("Selecione um cliente para registrar pagamento no convênio.");
                            return;
                        }
                        // Procura por id e adiciona ao combo se nao existir
                        // (mesmo motivo do bloco do botao Finalizar).
                        boolean encontrado = false;
                        for (int i = 0; i < clienteCombo.getItemCount(); i++) {
                            Item it = clienteCombo.getItemAt(i);
                            if (it != null && it.id() == clienteSelecionado.id()) {
                                clienteCombo.setSelectedIndex(i);
                                encontrado = true;
                                break;
                            }
                        }
                        if (!encontrado) {
                            clienteCombo.addItem(clienteSelecionado);
                            clienteCombo.setSelectedItem(clienteSelecionado);
                        }
                        clienteBusca.setText(clienteSelecionado.text());
                    }
                }
                BigDecimal valor;
                if ("PIX".equals(forma) || "DEBITO".equals(forma) || "CREDITO".equals(forma)) {
                    BigDecimal total = cartTotalLiquido().max(BigDecimal.ZERO);
                    BigDecimal recebidoAplicado = pagamentosDigitados.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    valor = total.subtract(recebidoAplicado).max(BigDecimal.ZERO);
                    if (valor.compareTo(BigDecimal.ZERO) <= 0) {
                        msg("Pagamento ja conferido.");
                        return;
                    }
                } else {
                    valor = money(valorRecebido.getText());
                    BusinessRules.requirePositive(valor, "Valor recebido");
                }
                if ("DINHEIRO".equals(forma)) {
                    BigDecimal total = cartTotalLiquido().max(BigDecimal.ZERO);
                    BigDecimal recebidoAplicado = pagamentosDigitados.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal faltante = total.subtract(recebidoAplicado).max(BigDecimal.ZERO);
                    if (faltante.compareTo(BigDecimal.ZERO) <= 0) {
                        msg("Pagamento ja conferido.");
                        return;
                    }
                    BigDecimal aplicado = valor.min(faltante);
                    pagamentosDigitados.put(forma, pagamentosDigitados.getOrDefault(forma, BigDecimal.ZERO).add(aplicado));
                    dinheiroRecebidoInformado[0] = dinheiroRecebidoInformado[0].add(valor);
                } else {
                    pagamentosDigitados.put(forma, pagamentosDigitados.getOrDefault(forma, BigDecimal.ZERO).add(valor));
                }
                valorRecebido.setText("0.00");
                recarregarTabelaPagamentos.run();
                valorRecebido.requestFocusInWindow();
                valorRecebido.selectAll();
            } catch (Exception ex) {
                error(ex);
            }
        };
        adicionarPagamento.addActionListener(e -> {
            addPagamentoAction.run();
        });
        confirmarPagamento.addActionListener(e -> addPagamentoAction.run());
        valorRecebido.addActionListener(e -> addPagamentoAction.run());
        removerPagamento.addActionListener(e -> {
            int row = pagamentosTable.getSelectedRow();
            if (row < 0) {
                msg("Selecione uma forma de pagamento para remover.");
                return;
            }
            int modelRow = pagamentosTable.convertRowIndexToModel(row);
            String forma = pagamentosModel.getValueAt(modelRow, 0).toString();
            if ("Convênio".equals(forma)) {
                forma = "FIADO";
            }
            pagamentosDigitados.remove(forma);
            if ("DINHEIRO".equals(forma)) {
                dinheiroRecebidoInformado[0] = BigDecimal.ZERO;
            }
            recarregarTabelaPagamentos.run();
        });
        bindShortcut(pagamentosTable, "removerPagamentoDel", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), removerPagamento::doClick);
        formaPagamento.addActionListener(e -> {
            String forma = Objects.requireNonNull(formaPagamento.getSelectedItem()).toString();
            boolean ehFiado = "FIADO".equals(forma);
            if (!ehFiado) {
                clienteCombo.setSelectedIndex(-1);
            }
            atualizarModoValorRecebido.run();
            paymentSummaryUpdater.run();
            // UX: ao escolher Convenio, ja foca no campo de busca inteligente
            // pra o operador comecar a digitar o nome do cliente direto.
            if (ehFiado) {
                clienteBusca.requestFocusInWindow();
            } else if (valorRecebido.isVisible()) {
                valorRecebido.requestFocusInWindow();
            } else {
                codigo.requestFocusInWindow();
            }
        });
        paymentFeedbackUpdater = paymentSummaryUpdater;
        pdvAfterSaleCleanup = () -> {
            pagamentosDigitados.clear();
            dinheiroRecebidoInformado[0] = BigDecimal.ZERO;
            pagamentosModel.setRowCount(0);
            dinheiro.setText(BigDecimal.ZERO.toPlainString());
            debito.setText(BigDecimal.ZERO.toPlainString());
            credito.setText(BigDecimal.ZERO.toPlainString());
            pix.setText(BigDecimal.ZERO.toPlainString());
            fiado.setText(BigDecimal.ZERO.toPlainString());
            creditoTroca.setText(BigDecimal.ZERO.toPlainString());
            // Limpa a busca de cliente do convenio pra proxima venda nao
            // herdar o cliente da venda anterior.
            clienteBusca.setText("");
            sugestoesClienteModel.clear();
            sugestoesClienteScroll.setVisible(false);
            paymentSummaryUpdater.run();
            atualizarModoValorRecebido.run();
            refreshCart();
            codigo.setText("");
            qtd.setText("0");
            SwingUtilities.invokeLater(codigo::requestFocusInWindow);
        };
        recarregarTabelaPagamentos.run();
        atualizarModoValorRecebido.run();
        Runnable refreshAddState = () -> {
            try {
                Item caixaSel = (Item) caixaCombo.getSelectedItem();
                Map<String, Object> caixaAtual = caixaSel == null ? null : one("select status, abertura_valor from caixas where id=?", caixaSel.id);
                boolean aberto = caixaAtual != null && "ABERTO".equals(String.valueOf(caixaAtual.get("status")));
                add.setEnabled(aberto);
                add.setToolTipText(aberto ? null : "Abra o caixa para adicionar itens.");
                if (aberto) {
                    BigDecimal abertura = money(caixaAtual.get("abertura_valor").toString());
                    BigDecimal dinheiroHoje = cashSalesToday(caixaSel.id);
                    BigDecimal saldoCaixa = abertura.add(dinheiroHoje);
                    fundo.setText(moneyInputText(saldoCaixa));
                    fundo.setToolTipText("<html>Abertura: R$ " + moneyInputText(abertura)
                            + "<br>Dinheiro recebido hoje: R$ " + moneyInputText(dinheiroHoje)
                            + "<br><b>Saldo atual: R$ " + moneyInputText(saldoCaixa) + "</b></html>");
                    fundo.setEditable(false);
                    fundo.setEnabled(false);
                    abrir.setText("Cx aberto");
                } else {
                    fundo.setEditable(true);
                    fundo.setEnabled(true);
                    fundo.setToolTipText("Valor de abertura (fundo de troco). Ex: 100,00");
                    abrir.setText("Abrir caixa");
                }
                if (caixaSel != null) {
                    BigDecimal cartaoHoje = cardSalesToday(caixaSel.id);
                    cartaoTopo.setText(moneyInputText(cartaoHoje));
                    cartaoTopo.setToolTipText("Total recebido hoje em DEBITO + CREDITO neste caixa.");
                } else {
                    cartaoTopo.setText("0,00");
                    cartaoTopo.setToolTipText(null);
                }
            } catch (Exception ex) {
                add.setEnabled(false);
                add.setToolTipText("Abra o caixa para adicionar itens.");
                fundo.setEditable(true);
                fundo.setEnabled(true);
                abrir.setText("Abrir caixa");
                cartaoTopo.setText("0,00");
            }
        };
        caixaCombo.addActionListener(e -> refreshAddState.run());
        refreshAddState.run();
        // Expoe o refresh do cabecalho para que outras partes do PDV
        // (finalizar venda, sangria, etc) possam atualizar os totais de
        // Fundo (dinheiro) e Cartao em tempo real.
        pdvHeaderRefresher = () -> SwingUtilities.invokeLater(refreshAddState);
        Runnable esconderSugestoes = () -> {
            sugestoesScroll.setVisible(false);
            sugestoesList.clearSelection();
            java.awt.Container parent = sugestoesScroll.getParent();
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }
        };
        Runnable aplicarSugestaoSelecionada = () -> {
            int idx = sugestoesList.getSelectedIndex();
            if (idx < 0 || idx >= sugestoesProdutos.size()) {
                return;
            }
            Map<String, Object> p = sugestoesProdutos.get(idx);
            escolhendoSugestao[0] = true;
            codigo.setText(tokenSugestaoProduto(p));
            escolhendoSugestao[0] = false;
            esconderSugestoes.run();
            qtd.requestFocusInWindow();
            qtd.selectAll();
        };
        Runnable atualizarSugestoes = () -> {
            // Autocomplete de produto desabilitado a pedido do operador.
            // O painel da lista nao esta no layout do PDV, entao a busca
            // visual foi removida. Mantemos apenas a estrutura da funcao
            // pra nao quebrar listeners e atalhos ja registrados.
            esconderSugestoes.run();
            if (true) return;
            // ----- codigo abaixo nao executa mais -----
            if (escolhendoSugestao[0]) {
                return;
            }
            String termo = codigo.getText().trim();
            if (termo.length() < 2) {
                esconderSugestoes.run();
                return;
            }
            if (!codigo.isFocusOwner()) {
                esconderSugestoes.run();
                return;
            }
            sugestoesProdutos.clear();
            sugestoesModel.clear();
            String termoNorm = termo.toLowerCase(Locale.ROOT);
            for (Map<String, Object> p : catalogoSugestoes) {
                if (!matchesSugestaoPrefix(p, termoNorm)) {
                    continue;
                }
                sugestoesProdutos.add(p);
                String nome = String.valueOf(p.getOrDefault("nome", ""));
                String cod = tokenSugestaoProduto(p);
                if (cod.equals(nome)) {
                    sugestoesModel.addElement(nome);
                } else {
                    sugestoesModel.addElement(nome + "  |  " + cod);
                }
                if (sugestoesProdutos.size() >= 8) {
                    break;
                }
            }
            if (sugestoesModel.isEmpty()) {
                esconderSugestoes.run();
                return;
            }
            sugestoesList.setSelectedIndex(0);
            sugestoesList.ensureIndexIsVisible(0);
            // Ajusta a altura da lista embutida com base no numero de
            // resultados (no maximo 6 linhas - o resto rola).
            int linhasVisiveis = Math.min(sugestoesModel.size(), 6);
            int altCelula = sugestoesList.getFixedCellHeight();
            if (altCelula <= 0) {
                altCelula = compactMode() ? 22 : 26;
            }
            int altScroll = altCelula * linhasVisiveis + 8;
            // Usa Short.MAX_VALUE em vez de 0 na largura - assim o BoxLayout
            // estica o scroll horizontalmente em vez de colapsar pra zero.
            sugestoesScroll.setPreferredSize(new Dimension(Short.MAX_VALUE, altScroll));
            sugestoesScroll.setMaximumSize(new Dimension(Short.MAX_VALUE, altScroll));
            sugestoesScroll.setMinimumSize(new Dimension(0, altScroll));
            sugestoesScroll.setVisible(true);
            // Revalida o painel direito (pai do scroll) - sem isso o
            // BoxLayout nao recalcula e a lista fica "escondida" mesmo
            // visivel. O .invalidate() forca o caminho ate o root.
            java.awt.Container parent = sugestoesScroll.getParent();
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }
        };
        javax.swing.Timer debounceSugestoes = new javax.swing.Timer(180, e -> atualizarSugestoes.run());
        debounceSugestoes.setRepeats(false);
        codigo.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { debounceSugestoes.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { debounceSugestoes.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { debounceSugestoes.restart(); }
        });
        sugestoesList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    aplicarSugestaoSelecionada.run();
                }
            }
        });
        bindShortcut(codigo, "sugestaoDown", KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), () -> {
            if (!sugestoesScroll.isVisible()) {
                return;
            }
            int next = Math.min(sugestoesModel.size() - 1, Math.max(0, sugestoesList.getSelectedIndex() + 1));
            sugestoesList.setSelectedIndex(next);
            sugestoesList.ensureIndexIsVisible(next);
        });
        bindShortcut(codigo, "sugestaoUp", KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), () -> {
            if (!sugestoesScroll.isVisible()) {
                return;
            }
            int next = Math.max(0, sugestoesList.getSelectedIndex() - 1);
            sugestoesList.setSelectedIndex(next);
            sugestoesList.ensureIndexIsVisible(next);
        });
        bindShortcut(codigo, "sugestaoEnter", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), () -> {
            if (sugestoesScroll.isVisible() && sugestoesList.getSelectedIndex() >= 0) {
                aplicarSugestaoSelecionada.run();
                return;
            }
            add.doClick();
        });
        bindShortcut(codigo, "sugestaoEsc", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), () -> {
            if (sugestoesScroll.isVisible()) {
                esconderSugestoes.run();
            } else {
                codigo.setText("");
                codigo.requestFocusInWindow();
            }
        });
        codigo.addActionListener(e -> {
            if (!sugestoesScroll.isVisible()) {
                add.doClick();
            }
        });
        qtd.addActionListener(e -> add.doClick());
        bindPdvShortcuts(panel, codigo, limpar, finalizar, cancelarItem, comprovante, cartTable);
        bindShortcut(cartTable, "cancelarItemTabela", KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), cancelarItem::doClick);
        attachChipAction(chipCodigo, codigo::requestFocusInWindow);
        attachChipAction(chipFinalizar, finalizar::doClick);
        attachChipAction(chipLimpar, limpar::doClick);
        attachChipAction(chipCancelar, cancelarItem::doClick);
        attachChipAction(chipCupom, comprovante::doClick);
        attachChipAction(chipDesconto, () -> aplicarDescontoLinhaSelecionada(cartTable));
        attachChipAction(chipEsc, () -> {
            codigo.setText("");
            codigo.requestFocusInWindow();
        });
        JScrollPane rightScroll = new JScrollPane(right);
        rightScroll.setBorder(BorderFactory.createEmptyBorder());
        rightScroll.getVerticalScrollBar().setUnitIncrement(compactMode() ? 24 : 18);
        rightScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        rightScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, cartCard, rightScroll);
        split.setResizeWeight(compactMode() ? 0.70 : (screenSize.width <= 1366 ? 0.62 : 0.68));
        split.setDividerLocation(compactMode() ? 0.72 : (screenSize.width <= 1366 ? 0.60 : 0.67));
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);
        panel.add(split, BorderLayout.CENTER);
        SwingUtilities.invokeLater(codigo::requestFocus);
        pdvFormaPagamentoRoot = formaPagamento;
        pdvFinalizarButton = finalizar;
        return panel;
    }

    private JLabel labelLight(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.BOLD, fontSize(11)));
        label.setForeground(new Color(54, 54, 64));
        return label;
    }

    private JPanel estoquePanel() {
        JPanel panel = page();
        // Form de cadastro de produto - layout COMPACTO (label colado ao
        // campo via GridBagLayout). Mesmo padrao do form de cliente.
        JPanel cadastroForm = new JPanel(new GridBagLayout());
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
        addCompactRow(cadastroForm, 0, "Nome", nome, 22, "Codigo barras", barras, 14);
        addCompactRow(cadastroForm, 1, "SKU", sku, 10, "Categoria", categoria, 14);
        addCompactRow(cadastroForm, 2, "Unidade", unidade, 6, "Custo", custo, 10);
        addCompactRow(cadastroForm, 3, "Venda", venda, 10, "Estoque inicial", estoque, 10);
        addCompactRow(cadastroForm, 4, "Estoque minimo", minimo, 10, "Prateleira", local, 12);
        addCompactRow(cadastroForm, 5, "Validade AAAA-MM-DD", validade, 12, "Observacoes", observacoes, 26);
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

        JPanel entradaForm = new JPanel(new GridBagLayout());
        entradaForm.setOpaque(false);
        JTextField entradaProdutoId = new JTextField();
        JTextField entradaQuantidade = new JTextField();
        JTextField entradaCusto = new JTextField();
        JTextField entradaLote = new JTextField();
        JTextField entradaValidade = new JTextField();
        JTextField entradaDocumento = new JTextField();
        JTextField entradaObservacao = new JTextField("Entrada manual de mercadoria");
        addCompactRow(entradaForm, 0, "Produto ID", entradaProdutoId, 8, "Quantidade", entradaQuantidade, 10);
        addCompactRow(entradaForm, 1, "Custo unitario", entradaCusto, 10, "Lote", entradaLote, 12);
        addCompactRow(entradaForm, 2, "Validade AAAA-MM-DD", entradaValidade, 12, "Documento", entradaDocumento, 16);
        addCompactRow(entradaForm, 3, "Observacao", entradaObservacao, 34);
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

        JButton cadastroAutomatico = button("\uD83D\uDD0D  Cadastrar por codigo de barras (auto)");
        cadastroAutomatico.setToolTipText("Escaneie ou digite o EAN/GTIN: o sistema busca automaticamente em bases publicas e preenche os dados.");
        cadastroAutomatico.addActionListener(e -> openBarcodeLookupDialog());

        // Dois cards LADO A LADO: "Cadastro de produto" a esquerda e
        // "Entrada manual de mercadoria" a direita. Aproveita o monitor
        // inteiro horizontalmente e evita rolagem vertical em notebooks.
        JPanel formsGrid = new JPanel(new GridLayout(1, 2, 12, 12));
        formsGrid.setOpaque(false);
        JPanel cadastroBody = new JPanel(new BorderLayout(0, 10));
        cadastroBody.setOpaque(false);
        cadastroBody.add(cadastroAutomatico, BorderLayout.NORTH);
        cadastroBody.add(formWithAction(cadastroForm, salvar), BorderLayout.CENTER);
        formsGrid.add(section("Cadastro de produto", cadastroBody));
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
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        JTextField razao = new JTextField();
        JTextField fantasia = new JTextField();
        JTextField cnpj = new JTextField();
        JTextField telefone = new JTextField();
        JTextField email = new JTextField();
        JTextField endereco = new JTextField();
        JTextField contato = new JTextField();
        addCompactRow(form, 0, "Razao social", razao, 22, "Fantasia", fantasia, 18);
        addCompactRow(form, 1, "CNPJ", cnpj, 14, "Telefone", telefone, 14);
        addCompactRow(form, 2, "Email", email, 22, "Endereco", endereco, 26);
        addCompactRow(form, 3, "Contato", contato, 18);
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
        panel.add(section("Cadastrar Fornecedor", formWithAction(form, salvar)));
        panel.add(Box.createVerticalStrut(12));
        panel.add(section("Fornecedores Cadastrados", table(
                "select razao_social as Razao, nome_fantasia as Fantasia, cnpj as CNPJ, " +
                "telefone as Telefone, email as Email, contato as Contato " +
                "from fornecedores order by razao_social")));
        return panel;
    }

    private JPanel xmlPanel() {
        JPanel panel = page();
        Path inbox = xmlInboxDir();

        // "Drop zone" visual: caixa pontilhada cinza com instrucoes (clicavel) + botoes embaixo.
        JPanel dropZone = new JPanel();
        dropZone.setLayout(new BoxLayout(dropZone, BoxLayout.Y_AXIS));
        dropZone.setBackground(new Color(0xFA, 0xFA, 0xFA));
        dropZone.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createDashedBorder(BORDER_STRONG, 6f, 4f),
                new EmptyBorder(compactMode() ? 18 : 30, 16, compactMode() ? 18 : 30, 16)));
        dropZone.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel uploadIcon = new JLabel("\u2B06");
        uploadIcon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, fontSize(compactMode() ? 28 : 40)));
        uploadIcon.setForeground(MARKET_GREEN);
        uploadIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel uploadText = new JLabel("Clique nos botoes abaixo para selecionar arquivos XML");
        uploadText.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(13)));
        uploadText.setForeground(TEXT_MUTED);
        uploadText.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel uploadFmt = new JLabel("Formatos aceitos: .xml");
        uploadFmt.setFont(new Font("Segoe UI", Font.ITALIC, fontSize(11)));
        uploadFmt.setForeground(TEXT_MUTED);
        uploadFmt.setAlignmentX(Component.CENTER_ALIGNMENT);
        dropZone.add(Box.createVerticalGlue());
        dropZone.add(uploadIcon);
        dropZone.add(Box.createVerticalStrut(8));
        dropZone.add(uploadText);
        dropZone.add(Box.createVerticalStrut(4));
        dropZone.add(uploadFmt);
        dropZone.add(Box.createVerticalGlue());

        JButton abrirPasta = button("Abrir pasta de XML");
        abrirPasta.addActionListener(e -> abrirPastaXml());
        abrirPasta.setToolTipText("Pasta padrao: " + inbox.toAbsolutePath());
        JButton importarMaisRecente = button("Importar ultimo XML da pasta");
        importarMaisRecente.addActionListener(e -> importarUltimoXmlDaPasta());
        JButton importar = button("Selecionar arquivos");
        importar.addActionListener(e -> importarXml());
        // Click em qualquer lugar da drop zone abre o diálogo de seleção (mesmo comportamento do botao).
        dropZone.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { importar.doClick(); }
        });

        JPanel actions = new JPanel(new GridLayout(1, 3, 8, 8));
        actions.setOpaque(false);
        actions.add(abrirPasta);
        actions.add(importarMaisRecente);
        actions.add(importar);

        // Painel lateral: aproveita o espaco vazio ao lado da drop zone com
        // dicas rapidas de "como funciona" + status do importador (pasta
        // padrao, total de NF-e ja importadas e ultima importacao).
        JPanel sidePanel = buildXmlSidePanel(inbox);

        // Layout horizontal da area superior do card: drop zone na esquerda
        // (estica), painel de dicas/status na direita com largura fixa.
        JPanel topRow = new JPanel(new BorderLayout(12, 0));
        topRow.setOpaque(false);
        topRow.add(dropZone, BorderLayout.CENTER);
        topRow.add(sidePanel, BorderLayout.EAST);

        JPanel uploadCard = new JPanel(new BorderLayout(0, 10));
        uploadCard.setOpaque(false);
        uploadCard.add(topRow, BorderLayout.CENTER);
        uploadCard.add(actions, BorderLayout.SOUTH);

        panel.add(section("Importar XML NF-e", uploadCard));
        panel.add(Box.createVerticalStrut(12));
        panel.add(section("NF-es Importadas", table(
                "select numero_nf as Numero, chave_acesso as ChaveAcesso, data as Emissao, " +
                "total as Total, importado_em as Importado " +
                "from notas_fiscais order by id desc limit 100")));
        return panel;
    }

    /**
     * Painel lateral do card "Importar XML NF-e": dicas rapidas e status
     * do importador (pasta padrao, total de NF-e importadas, ultima NF
     * importada). Preenche o espaco vazio que sobrava ao lado da drop zone.
     */
    private JPanel buildXmlSidePanel(Path inbox) {
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setOpaque(true);
        side.setBackground(new Color(0xF1, 0xF8, 0xE9));
        side.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xC8, 0xE6, 0xC9), 1),
                new EmptyBorder(10, 12, 10, 12)));
        // Largura fixa pra garantir que o painel nao engula a drop zone.
        int sideW = compactMode() ? 240 : 300;
        side.setPreferredSize(new java.awt.Dimension(sideW, 10));
        side.setMaximumSize(new java.awt.Dimension(sideW, Integer.MAX_VALUE));

        JLabel titulo = new JLabel("Como funciona");
        titulo.setFont(new Font("Segoe UI", Font.BOLD, fontSize(13)));
        titulo.setForeground(MARKET_GREEN);
        titulo.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(titulo);
        side.add(Box.createVerticalStrut(6));

        JLabel passos = new JLabel(
                "<html>"
                + "<b>1.</b> Salve seus arquivos <b>.xml</b> da NF-e na pasta padrao.<br>"
                + "<b>2.</b> Clique em <b>Importar ultimo XML</b> para importar o arquivo mais recente.<br>"
                + "<b>3.</b> Ou use <b>Selecionar arquivos</b> para escolher manualmente um ou varios."
                + "</html>");
        passos.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
        passos.setForeground(TEXT_DARK);
        passos.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(passos);
        side.add(Box.createVerticalStrut(10));

        // Linha separadora discreta.
        JPanel sep = new JPanel();
        sep.setBackground(new Color(0xC8, 0xE6, 0xC9));
        sep.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(sep);
        side.add(Box.createVerticalStrut(8));

        JLabel statusTitulo = new JLabel("Status");
        statusTitulo.setFont(new Font("Segoe UI", Font.BOLD, fontSize(12)));
        statusTitulo.setForeground(MARKET_GREEN);
        statusTitulo.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(statusTitulo);
        side.add(Box.createVerticalStrut(4));

        // Pasta padrao (com elipse no meio se for muito longa).
        String pasta = inbox.toAbsolutePath().toString();
        String pastaCurta = pasta.length() > 42
                ? pasta.substring(0, 18) + "..." + pasta.substring(pasta.length() - 22)
                : pasta;
        JLabel pastaLbl = new JLabel("<html><b>Pasta:</b> " + pastaCurta + "</html>");
        pastaLbl.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
        pastaLbl.setForeground(TEXT_DARK);
        pastaLbl.setToolTipText(pasta);
        pastaLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(pastaLbl);
        side.add(Box.createVerticalStrut(4));

        int totalNotas = safeInt("select count(*) from notas_fiscais");
        JLabel totalLbl = new JLabel("<html><b>NF-e importadas:</b> " + totalNotas + "</html>");
        totalLbl.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
        totalLbl.setForeground(TEXT_DARK);
        totalLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(totalLbl);
        side.add(Box.createVerticalStrut(4));

        String ultima = "Nenhuma";
        try {
            Map<String, Object> row = one(
                    "select numero_nf, importado_em from notas_fiscais order by id desc limit 1");
            if (row != null && !row.isEmpty()) {
                Object num = row.get("numero_nf");
                Object dt = row.get("importado_em");
                String numStr = num == null ? "-" : String.valueOf(num);
                String dtStr = dt == null ? "" : " (" + String.valueOf(dt) + ")";
                ultima = "NF " + numStr + dtStr;
            }
        } catch (Exception ignored) { /* sem ultima importacao */ }
        JLabel ultimaLbl = new JLabel("<html><b>Ultima:</b> " + ultima + "</html>");
        ultimaLbl.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
        ultimaLbl.setForeground(TEXT_DARK);
        ultimaLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(ultimaLbl);

        side.add(Box.createVerticalGlue());
        return side;
    }

    private JPanel fiadoPanel() {
        JPanel panel = page();
        // Form COMPACTO usando GridBagLayout: labels com largura minima
        // (colados ao campo) e os campos esticam pra ocupar a linha.
        // Visualmente fica "Nome [_______] CPF [_______]" sem espaco
        // grande entre o label e o input. Mesmo padrao pode ser
        // replicado em outros formularios (compactForm helper).
        JPanel form = new JPanel(new GridBagLayout());
        JTextField nome = new JTextField();
        // Campo de CPF com placeholder visual "000.000.000-00" (cinza claro
        // quando vazio) e mascara dinamica enquanto o operador digita.
        JTextField cpf = createPlaceholderField("000.000.000-00");
        bindCpfMask(cpf);
        JTextField telefone = new JTextField();
        JTextField endereco = new JTextField();
        JTextField limite = new JTextField("500,00");
        // Mascara LIVE: estilo calculadora - operador digita "150000" e ve
        // "1.500,00" enquanto digita; "2200" -> "22,00".
        bindMoneyMaskLive(limite);
        addCompactRow(form, 0, "Nome", nome, 20, "CPF", cpf, 14);
        addCompactRow(form, 1, "Telefone", telefone, 14, "Endereco", endereco, 22);
        addCompactRow(form, 2, "Limite", limite, 12);
        // Aviso visual: este formulario e SO PARA CADASTRAR cliente novo.
        // A edicao de um cliente ja cadastrado e feita exclusivamente
        // pelo botao "Alterar cliente selecionado" (ou duplo clique na tabela).
        JLabel ajudaCadastro = new JLabel(
                "<html>\u2139 Use este formul\u00e1rio apenas para <b>cadastrar um cliente novo</b>." +
                "<br>Para alterar dados de um cliente j\u00e1 cadastrado, selecione na tabela " +
                "abaixo e clique em <b>Alterar cliente selecionado</b> (ou d\u00ea duplo clique).</html>");
        ajudaCadastro.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
        ajudaCadastro.setForeground(new Color(0x5D, 0x40, 0x37));
        ajudaCadastro.setOpaque(true);
        ajudaCadastro.setBackground(new Color(0xFF, 0xF8, 0xE1));
        ajudaCadastro.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xFB, 0xC0, 0x2D), 1),
                new EmptyBorder(6, 10, 6, 10)));
        JButton salvar = button("\u2795  Cadastrar novo");
        salvar.setToolTipText("Insere um cliente novo. Para editar um existente, use o botao 'Alterar cliente selecionado' abaixo.");
        salvar.addActionListener(e -> {
            try {
                requireFiadoAccess();
                BusinessRules.requireNotBlank(nome.getText(), "Nome do cliente");
                BigDecimal limiteCredito = money(limite.getText());
                BusinessRules.requireNonNegative(limiteCredito, "Limite de credito");
                String cpfTrim = cpf.getText() == null ? "" : cpf.getText().trim();
                // Validacao do CPF: se o operador digitou algo, tem que
                // ser um CPF com 11 digitos e os digitos verificadores
                // corretos. Se quiser cadastrar sem CPF, deixa em branco.
                if (!cpfTrim.isEmpty() && !isValidCpf(cpfTrim)) {
                    throw new AppException(
                            "CPF invalido. Verifique os numeros digitados ou deixe o campo em branco.");
                }
                // CPF e armazenado SEM mascara (so digitos) pra padronizar
                // o banco - independente de quando foi cadastrado, o
                // dado fica em formato canonico.
                String cpfDigitos = cpfTrim.replaceAll("\\D", "");
                // Bloqueia INSERT se ja existe cliente com este CPF: forca o
                // operador a usar o fluxo de edicao em vez de sobrescrever
                // dados sem querer. Normaliza ambos lados (banco pode ter
                // dado antigo com mascara) pra evitar falso "nao existe".
                if (!cpfDigitos.isEmpty()) {
                    Map<String, Object> existente = one(
                            "select id, nome from clientes " +
                            "where replace(replace(coalesce(cpf,''), '.', ''), '-', '') = ?",
                            cpfDigitos);
                    if (existente != null) {
                        long idExistente = ((Number) existente.get("id")).longValue();
                        String nomeExistente = String.valueOf(existente.get("nome"));
                        throw new AppException(
                                "J\u00e1 existe um cliente com este CPF: \"" + nomeExistente
                                        + "\" (#" + idExistente + ")."
                                        + "\nPara alterar os dados dele, selecione na tabela "
                                        + "\"Clientes\" e clique em \"Alterar cliente selecionado\".");
                    }
                }
                update("""
                    insert into clientes (nome, cpf, telefone, endereco, limite_credito, bloqueado)
                    values (?, ?, ?, ?, ?, 0)
                    """,
                        nome.getText(), cpfDigitos.isEmpty() ? null : cpfDigitos,
                        telefone.getText(), endereco.getText(), limiteCredito);
                audit("CLIENTE_CADASTRADO", nome.getText() + (cpfDigitos.isEmpty() ? "" : " | CPF " + formatCpf(cpfDigitos)));
                // Limpa o formulario para o proximo cadastro.
                nome.setText("");
                cpf.setText("");
                telefone.setText("");
                endereco.setText("");
                limite.setText("500,00");
                // Refresh leve da aba: novo cliente aparece nas tabelas
                // sem reconstruir a janela (mantendo carrinho do PDV etc).
                convenioRefresher.run();
            } catch (Exception ex) { error(ex); }
        });
        JButton pagar = button("Registrar pagamento de convênio");
        pagar.addActionListener(e -> pagarFiado());

        // Tabela e botoes de acao precisam existir ANTES de montarmos a area
        // de cadastro, porque o botao "Alterar" fica dentro dela (logo abaixo
        // dos campos do formulario) e referencia a tabela.
        JTable tabelaClientes = table("""
            select c.id as ID, c.nome as Nome, c.cpf as CPF,
                   c.limite_credito as Limite,
                   coalesce(sum(f.valor - f.valor_pago),0) as Divida
            from clientes c left join fiado f on f.cliente_id=c.id and f.status='ABERTO'
            where c.bloqueado = 0
            group by c.id order by c.nome
            """);
        JButton alterarCliente = button("\u270F\uFE0F  Alterar selecionado");
        alterarCliente.setToolTipText("Selecione um cliente na tabela \"Clientes\" abaixo e clique aqui para abrir todos os dados para edicao. Tambem funciona com duplo clique na linha.");
        alterarCliente.addActionListener(e -> alterarClienteFiado(tabelaClientes));
        // Duplo clique numa linha tambem abre a edicao - atalho natural do PDV.
        tabelaClientes.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent ev) {
                if (ev.getClickCount() == 2 && tabelaClientes.getSelectedRow() >= 0) {
                    alterarClienteFiado(tabelaClientes);
                }
            }
        });
        // Botao "Dar baixa no convenio": seleciona o cliente, informa o valor
        // pago e o sistema distribui automaticamente entre os lancamentos
        // em aberto (FIFO - mais antigos primeiro), marcando como PAGO os
        // que forem totalmente quitados. E a alternativa amigavel ao botao
        // antigo de "Registrar pagamento" que pedia ID do lancamento.
        JButton darBaixa = button("\uD83D\uDCB5  Dar baixa no conv\u00eanio");
        darBaixa.setToolTipText("Abre uma busca inteligente: digite parte do nome ou CPF do cliente, escolha e informe o valor pago. O sistema quita os lancamentos do mais antigo ao mais novo.");
        darBaixa.addActionListener(e -> darBaixaConvenio());
        JButton excluirCliente = button("Excluir cliente selecionado");
        excluirCliente.addActionListener(e -> excluirClienteFiado(tabelaClientes));

        JPanel cadastroBody = new JPanel(new BorderLayout(0, 8));
        cadastroBody.setOpaque(false);
        cadastroBody.add(ajudaCadastro, BorderLayout.NORTH);
        cadastroBody.add(form, BorderLayout.CENTER);
        panel.add(section("Cadastrar novo cliente", cadastroBody));
        // Linha de acoes do cadastro: "Cadastrar novo" + "Alterar selecionado"
        // + "Dar baixa no convenio". Tres botoes lado a lado, compactos, com
        // o mesmo grid pra manter alinhamento. Os dois ultimos atuam sobre
        // a linha selecionada na tabela Clientes (mais abaixo no painel).
        JPanel acoesCadastro = new JPanel(new GridLayout(1, 3, 6, 0));
        acoesCadastro.setOpaque(false);
        acoesCadastro.add(salvar);
        acoesCadastro.add(alterarCliente);
        acoesCadastro.add(darBaixa);
        panel.add(acoesCadastro);

        panel.add(section("Clientes", tabelaClientes));
        // Apenas o botao Excluir fica embaixo da tabela; o Alterar foi movido
        // para a area de cadastro (acima), conforme pedido do operador.
        panel.add(excluirCliente);
        panel.add(pagar);
        // Tabela "Convenio em aberto": guardamos a referencia (e a SQL)
        // para que o convenioRefresher possa recarregar os dados sem
        // reconstruir o painel inteiro - assim, toda venda no convenio
        // (ou pagamento de convenio) atualiza essa lista em tempo real.
        final String SQL_CLIENTES_CONVENIO = """
            select c.id as ID, c.nome as Nome, c.cpf as CPF,
                   c.limite_credito as Limite,
                   coalesce(sum(f.valor - f.valor_pago),0) as Divida
            from clientes c left join fiado f on f.cliente_id=c.id and f.status='ABERTO'
            where c.bloqueado = 0
            group by c.id order by c.nome
            """;
        final String SQL_CONVENIO_ABERTO =
                "select f.id as ID, c.nome as Cliente, f.valor as Valor, "
                + "f.valor_pago as Pago, f.data_criacao as Data from fiado f "
                + "join clientes c on c.id=f.cliente_id "
                + "where f.status='ABERTO' order by f.id desc";
        JTable tabelaConvenioAberto = table(SQL_CONVENIO_ABERTO);
        panel.add(section("Convênio em aberto", tabelaConvenioAberto));
        // Conecta o refresher global: chamado pelo PDV apos cada venda
        // FIADO e por outros pontos que mexem em fiado.
        convenioRefresher = () -> SwingUtilities.invokeLater(() -> {
            reloadTableSql(tabelaClientes, SQL_CLIENTES_CONVENIO);
            reloadTableSql(tabelaConvenioAberto, SQL_CONVENIO_ABERTO);
        });
        return panel;
    }

    /**
     * Recarrega o conteudo de um JTable existente executando a SQL e
     * substituindo as linhas/colunas no DefaultTableModel atual. Mantem
     * a instancia da JTable (e seus listeners) - ideal para "atualizar
     * sem reconstruir o painel".
     */
    private void reloadTableSql(JTable tabela, String sql, Object... args) {
        try {
            List<Map<String, Object>> data = rows(sql, args);
            Vector<String> cols = new Vector<>();
            Vector<Vector<Object>> lines = new Vector<>();
            if (!data.isEmpty()) cols.addAll(data.get(0).keySet());
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
            DefaultTableModel novo = new DefaultTableModel(lines, cols) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            };
            tabela.setModel(novo);
        } catch (Exception e) {
            // Falha silenciosa: refresh nao e operacao critica.
            appLog("WARN", "RELOAD_TABELA", "Falha ao recarregar tabela", e.getMessage());
        }
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

        // Form de novo lancamento (Pagar/Receber) - mesmo padrao compacto.
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        JComboBox<String> tipo = new JComboBox<>(new String[]{"PAGAR", "RECEBER"});
        JTextField descricao = new JTextField();
        JTextField parceiro = new JTextField();
        JTextField categoria = new JTextField();
        JTextField valor = new JTextField("0");
        JTextField vencimento = new JTextField(LocalDate.now().plusDays(7).toString());
        JTextField observacao = new JTextField();
        addCompactRow(form, 0, "Tipo", tipo, 12, "Descricao", descricao, 22);
        addCompactRow(form, 1, "Parceiro", parceiro, 18, "Categoria", categoria, 14);
        addCompactRow(form, 2, "Valor", valor, 10, "Vencimento AAAA-MM-DD", vencimento, 12);
        addCompactRow(form, 3, "Observacao", observacao, 34);
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
        panel.setBorder(BorderFactory.createEmptyBorder(compactMode() ? 4 : 6, compactMode() ? 6 : 8, compactMode() ? 6 : 10, compactMode() ? 6 : 8));
        JLabel tituloRel = new JLabel("Relatorios gerenciais");
        tituloRel.setFont(new Font("Segoe UI", Font.BOLD, fontSize(compactMode() ? 17 : 20)));
        tituloRel.setForeground(new Color(20, 20, 20));
        tituloRel.setBorder(new EmptyBorder(0, 0, compactMode() ? 4 : 8, 0));
        tituloRel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(tituloRel);

        JPanel filtros = new JPanel(new FlowLayout(FlowLayout.LEFT, compactMode() ? 6 : 10, 0));
        filtros.setOpaque(false);
        filtros.setAlignmentX(Component.LEFT_ALIGNMENT);
        filtros.add(label("Data"));
        java.util.Date dataInicial = java.util.Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        SpinnerDateModel modeloData = new SpinnerDateModel(dataInicial, null, null, Calendar.DAY_OF_MONTH);
        JSpinner spData = new JSpinner(modeloData);
        spData.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));
        spData.setEditor(new JSpinner.DateEditor(spData, "dd/MM/yyyy"));
        Dimension spPref = spData.getPreferredSize();
        spData.setPreferredSize(new Dimension(Math.min(scale(118), spPref.width + scale(8)), spPref.height));
        filtros.add(spData);
        JButton btnCalendario = new JButton("\uD83D\uDCC5");
        btnCalendario.setFont(btnCalendario.getFont().deriveFont(Font.PLAIN, fontSize(14)));
        btnCalendario.setToolTipText("Abrir calendario");
        btnCalendario.setFocusPainted(false);
        btnCalendario.setMargin(new Insets(2, 6, 2, 6));
        btnCalendario.setBackground(PANEL_BG);
        btnCalendario.setForeground(new Color(40, 40, 50));
        btnCalendario.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 206, 216)),
                new EmptyBorder(2, 4, 2, 4)));
        filtros.add(btnCalendario);
        filtros.add(Box.createHorizontalStrut(compactMode() ? 6 : 10));
        filtros.add(label("Caixa"));
        JComboBox<Item> filtroCaixa = new JComboBox<>();
        filtroCaixa.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(13)));
        try {
            filtroCaixa.addItem(new Item(-1L, "Ambos os caixas"));
            for (Map<String, Object> cx : rows("select id, numero from caixas order by cast(numero as integer)")) {
                long id = ((Number) cx.get("id")).longValue();
                filtroCaixa.addItem(new Item(id, "Caixa " + cx.get("numero")));
            }
        } catch (Exception ignored) {
            filtroCaixa.addItem(new Item(-1L, "Ambos os caixas"));
        }
        filtros.add(filtroCaixa);
        filtros.add(Box.createHorizontalStrut(compactMode() ? 6 : 10));
        JButton btnAtualizarRel = button("Atualizar relatorio");
        filtros.add(btnAtualizarRel);
        panel.add(filtros);
        panel.add(Box.createVerticalStrut(compactMode() ? 4 : 6));

        JPanel relatorioBody = new JPanel();
        relatorioBody.setOpaque(false);
        relatorioBody.setLayout(new BoxLayout(relatorioBody, BoxLayout.Y_AXIS));
        relatorioBody.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(relatorioBody);

        Runnable rebuild = () -> {
            try {
                LocalDate dia = ((java.util.Date) spData.getValue()).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                Item cxItem = (Item) filtroCaixa.getSelectedItem();
                Long caixaFiltro = (cxItem == null || cxItem.id < 0) ? null : cxItem.id;
                relatorioBody.removeAll();
                relatorioBody.add(relatoriosKpiRow(dia, caixaFiltro));
                relatorioBody.add(Box.createVerticalStrut(compactMode() ? 6 : 8));

                JPanel linhaVendas = new JPanel(new GridLayout(1, 2, compactMode() ? 8 : 10, compactMode() ? 8 : 10));
                linhaVendas.setOpaque(false);
                String sqlVendas = """
                        select v.id as Venda, datetime(v.timestamp) as Data, c.numero as Caixa, u.nome as Operador, v.total as Total, v.forma_pagamento as Pagamento
                        from vendas v join caixas c on c.id=v.caixa_id join usuarios u on u.id=v.operador_id
                        where v.status='CONCLUIDA' and date(v.timestamp)=date(?)
                        """ + (caixaFiltro == null ? "" : " and v.caixa_id=? ")
                        + " order by v.id desc";
                linhaVendas.add(section("Vendas do dia", table(sqlVendas,
                        caixaFiltro == null ? new Object[]{dia.toString()} : new Object[]{dia.toString(), caixaFiltro}), true));

                String sqlProd = """
                        select p.nome as Produto, sum(vi.quantidade) as Quantidade, sum(vi.quantidade*vi.preco_unitario) as Total
                        from venda_itens vi join produtos p on p.id=vi.produto_id join vendas v on v.id=vi.venda_id
                        where v.status='CONCLUIDA' and date(v.timestamp)=date(?)
                        """ + (caixaFiltro == null ? "" : " and v.caixa_id=? ")
                        + " group by p.id order by Quantidade desc limit 20";
                linhaVendas.add(section("Produtos mais vendidos (dia)", table(sqlProd,
                        caixaFiltro == null ? new Object[]{dia.toString()} : new Object[]{dia.toString(), caixaFiltro}), true));
                relatorioBody.add(linhaVendas);
                relatorioBody.add(Box.createVerticalStrut(compactMode() ? 6 : 8));

                JPanel linhaFinanceiro = new JPanel(new GridLayout(1, 2, compactMode() ? 8 : 10, compactMode() ? 8 : 10));
                linhaFinanceiro.setOpaque(false);
                linhaFinanceiro.add(section("Fluxo financeiro (baixas do dia)", table("""
                        select id as ID, tipo as Tipo, descricao as Descricao, valor_total as Total, valor_baixado as Baixado,
                               forma_baixa as Forma, baixado_em as Data
                        from financeiro_lancamentos
                        where baixado_em is not null and date(baixado_em)=date(?)
                        order by id desc
                        """, dia.toString()), true));
                linhaFinanceiro.add(section("Convênio em aberto", table("""
                        select c.nome as Cliente, sum(f.valor-f.valor_pago) as Aberto
                        from fiado f join clientes c on c.id=f.cliente_id
                        where f.status='ABERTO'
                        group by c.id
                        order by Aberto desc
                        """), true));
                relatorioBody.add(linhaFinanceiro);
                relatorioBody.add(Box.createVerticalStrut(compactMode() ? 6 : 8));

                JPanel linhaOperacional = new JPanel(new GridLayout(1, 2, compactMode() ? 8 : 10, compactMode() ? 8 : 10));
                linhaOperacional.setOpaque(false);
                linhaOperacional.add(section("Fechamento diario consolidado", summaryTable(cashReportService.dailySummary(caixaFiltro, dia)), true));
                String sqlDev = """
                        select d.id as Devolucao, d.venda_id as Venda, d.tipo as Tipo, d.forma_destino as Destino, d.valor_total as Valor, d.criado_em as Data
                        from devolucoes d
                        where date(d.criado_em)=date(?)
                        """ + (caixaFiltro == null ? "" : " and d.caixa_id=? ")
                        + " order by d.id desc";
                linhaOperacional.add(section("Devolucoes do dia", table(sqlDev,
                        caixaFiltro == null ? new Object[]{dia.toString()} : new Object[]{dia.toString(), caixaFiltro}), true));
                relatorioBody.add(linhaOperacional);
                relatorioBody.add(Box.createVerticalStrut(compactMode() ? 6 : 8));

                JPanel linhaHistorico = new JPanel(new GridLayout(1, 2, compactMode() ? 8 : 10, compactMode() ? 8 : 10));
                linhaHistorico.setOpaque(false);
                linhaHistorico.add(section("Lucro por dia", table("""
                        select date(v.timestamp) as Dia, sum(vi.quantidade*vi.preco_unitario) as Receita, sum(vi.quantidade*vi.custo_unitario) as Custo,
                        sum(vi.quantidade*(vi.preco_unitario-vi.custo_unitario)) as Lucro
                        from venda_itens vi join vendas v on v.id=vi.venda_id where v.status='CONCLUIDA' group by date(v.timestamp) order by Dia desc
                        """), true));
                linhaHistorico.add(section("Validades proximas por lote", table("""
                        select p.nome as Produto, coalesce(e.lote,'-') as Lote, e.validade as Validade, e.quantidade as Quantidade, e.documento as Documento
                        from entradas_estoque e join produtos p on p.id = e.produto_id
                        where e.validade is not null and date(e.validade) <= date('now','+30 day')
                        order by date(e.validade), p.nome
                        """), true));
                relatorioBody.add(linhaHistorico);
                relatorioBody.add(Box.createVerticalStrut(compactMode() ? 6 : 8));
                String sqlComp = """
                        select cv.venda_id as Venda, cv.arquivo_txt as TXT, cv.arquivo_pdf as PDF, cv.gerado_em as GeradoEm
                        from comprovantes_venda cv
                        join vendas v on v.id = cv.venda_id
                        where date(cv.gerado_em)=date(?)
                        """ + (caixaFiltro == null ? "" : " and v.caixa_id=? ")
                        + " order by cv.id desc limit 20";
                relatorioBody.add(section("Comprovantes do dia", table(sqlComp,
                        caixaFiltro == null ? new Object[]{dia.toString()} : new Object[]{dia.toString(), caixaFiltro}), true));
                relatorioBody.revalidate();
                relatorioBody.repaint();
            } catch (Exception ex) {
                error(ex);
            }
        };
        btnAtualizarRel.addActionListener(e -> rebuild.run());
        spData.addChangeListener(e -> rebuild.run());
        filtroCaixa.addActionListener(e -> rebuild.run());
        btnCalendario.addActionListener(e -> {
            LocalDate atual = ((java.util.Date) spData.getValue()).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            openMiniCalendarPopup(btnCalendario, atual, picked -> {
                java.util.Date asDate = java.util.Date.from(picked.atStartOfDay(ZoneId.systemDefault()).toInstant());
                spData.setValue(asDate);
                rebuild.run();
            });
        });
        rebuild.run();
        return panel;
    }

    /** Calendario compacto (mes em grade); domingo na primeira coluna. */
    private void openMiniCalendarPopup(Component anchor, LocalDate current, Consumer<LocalDate> onDayPicked) {
        final YearMonth[] ymHolder = {YearMonth.from(current)};
        JPopupMenu popup = new JPopupMenu();
        popup.setLayout(new BorderLayout(0, 2));
        JPanel header = new JPanel(new BorderLayout(0, 0));
        header.setOpaque(false);
        JButton prev = new JButton("<");
        JButton next = new JButton(">");
        for (JButton b : new JButton[]{prev, next}) {
            b.setFocusPainted(false);
            b.setMargin(new Insets(1, 6, 1, 6));
            b.setFont(new Font("Segoe UI", Font.BOLD, fontSize(12)));
            b.setBackground(PANEL_BG);
            b.setForeground(new Color(40, 40, 50));
            b.setBorder(BorderFactory.createLineBorder(new Color(190, 198, 210)));
        }
        JLabel mesAno = new JLabel("", SwingConstants.CENTER);
        mesAno.setFont(new Font("Segoe UI", Font.BOLD, fontSize(12)));
        JPanel gridWrap = new JPanel(new BorderLayout());
        gridWrap.setOpaque(false);
        final JPanel[] gridRef = new JPanel[1];
        Runnable refreshMes = () -> mesAno.setText(ymHolder[0].format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("pt-BR"))));
        Runnable rebuildGrid = () -> {
            if (gridRef[0] != null) {
                gridWrap.remove(gridRef[0]);
            }
            JPanel grid = new JPanel(new GridLayout(0, 7, 2, 2));
            grid.setOpaque(false);
            String[] diasSem = {"Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sab"};
            for (String ds : diasSem) {
                JLabel h = new JLabel(ds, SwingConstants.CENTER);
                h.setFont(new Font("Segoe UI", Font.BOLD, fontSize(9)));
                h.setForeground(new Color(90, 98, 110));
                grid.add(h);
            }
            YearMonth ym = ymHolder[0];
            boolean mesDaSelecao = YearMonth.from(current).equals(ym);
            LocalDate primeiro = ym.atDay(1);
            int offset = primeiro.getDayOfWeek().getValue() % 7;
            int diasNoMes = ym.lengthOfMonth();
            for (int i = 0; i < offset; i++) {
                grid.add(new JLabel());
            }
            for (int d = 1; d <= diasNoMes; d++) {
                LocalDate dia = ym.atDay(d);
                JButton cell = new JButton(String.valueOf(d));
                cell.setFocusPainted(false);
                cell.setMargin(new Insets(1, 0, 1, 0));
                cell.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
                cell.setBackground(mesDaSelecao && dia.equals(current) ? new Color(220, 235, 255) : PANEL_BG);
                cell.setBorder(BorderFactory.createLineBorder(new Color(210, 216, 226)));
                cell.addActionListener(ev -> {
                    onDayPicked.accept(dia);
                    popup.setVisible(false);
                });
                grid.add(cell);
            }
            int used = 7 + offset + diasNoMes;
            int pad = used % 7;
            if (pad != 0) {
                for (int i = 0; i < 7 - pad; i++) {
                    grid.add(new JLabel());
                }
            }
            gridRef[0] = grid;
            gridWrap.add(grid, BorderLayout.CENTER);
            gridWrap.revalidate();
            gridWrap.repaint();
        };
        prev.addActionListener(ev -> {
            ymHolder[0] = ymHolder[0].minusMonths(1);
            refreshMes.run();
            rebuildGrid.run();
        });
        next.addActionListener(ev -> {
            ymHolder[0] = ymHolder[0].plusMonths(1);
            refreshMes.run();
            rebuildGrid.run();
        });
        header.add(prev, BorderLayout.WEST);
        header.add(mesAno, BorderLayout.CENTER);
        header.add(next, BorderLayout.EAST);
        refreshMes.run();
        rebuildGrid.run();
        JPanel body = new JPanel(new BorderLayout(0, 4));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(4, 6, 6, 6));
        body.add(header, BorderLayout.NORTH);
        body.add(gridWrap, BorderLayout.CENTER);
        popup.add(body, BorderLayout.CENTER);
        popup.show(anchor, 0, anchor.getHeight());
    }

    private JPanel relatoriosKpiRow(LocalDate dia, Long caixaFiltro) {
        JPanel kpis = new JPanel(new GridLayout(1, 4, compactMode() ? 6 : 8, compactMode() ? 6 : 8));
        kpis.setOpaque(false);
        BigDecimal totVendas;
        BigDecimal totTicket;
        BigDecimal totCard;
        BigDecimal totDev;
        if (caixaFiltro == null) {
            totVendas = safeMoney("""
                    select coalesce(sum(total),0)
                    from vendas
                    where status='CONCLUIDA' and date(timestamp)=date(?)
                    """, dia.toString());
            totTicket = safeMoney("""
                    select coalesce(avg(total),0)
                    from vendas
                    where status='CONCLUIDA' and date(timestamp)=date(?)
                    """, dia.toString());
            totCard = safeMoney("""
                    select coalesce(sum(vp.valor),0)
                    from venda_pagamentos vp
                    join vendas v on v.id=vp.venda_id
                    where v.status='CONCLUIDA'
                      and date(v.timestamp)=date(?)
                      and vp.forma in ('DEBITO','CREDITO')
                    """, dia.toString());
            totDev = safeMoney("""
                    select coalesce(sum(valor_total),0)
                    from devolucoes d
                    where date(d.criado_em)=date(?)
                    """, dia.toString());
        } else {
            totVendas = safeMoney("""
                    select coalesce(sum(total),0)
                    from vendas
                    where status='CONCLUIDA' and date(timestamp)=date(?) and caixa_id=?
                    """, dia.toString(), caixaFiltro);
            totTicket = safeMoney("""
                    select coalesce(avg(total),0)
                    from vendas
                    where status='CONCLUIDA' and date(timestamp)=date(?) and caixa_id=?
                    """, dia.toString(), caixaFiltro);
            totCard = safeMoney("""
                    select coalesce(sum(vp.valor),0)
                    from venda_pagamentos vp
                    join vendas v on v.id=vp.venda_id
                    where v.status='CONCLUIDA'
                      and date(v.timestamp)=date(?)
                      and vp.forma in ('DEBITO','CREDITO')
                      and v.caixa_id=?
                    """, dia.toString(), caixaFiltro);
            totDev = safeMoney("""
                    select coalesce(sum(valor_total),0)
                    from devolucoes d
                    where date(d.criado_em)=date(?) and d.caixa_id=?
                    """, dia.toString(), caixaFiltro);
        }
        kpis.add(metricCardRelatorio("Vendas do dia", moneyText(totVendas), MARKET_GREEN_2));
        kpis.add(metricCardRelatorio("Ticket medio", moneyText(totTicket), MARKET_GREEN));
        kpis.add(metricCardRelatorio("Cartao (deb/cred)", moneyText(totCard), MARKET_ORANGE));
        kpis.add(metricCardRelatorio("Devolucoes do dia", moneyText(totDev), MARKET_RED));
        return kpis;
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
            ensureCaixaAbertoParaVenda();
            BusinessRules.requireNotBlank(codigo, "Codigo ou descricao");
            List<Map<String, Object>> encontrados = rows("""
                select *
                from produtos
                where ativo=1
                  and (
                    codigo_barras=? or sku=? or codigo_interno=?
                    or lower(nome) like lower(?)
                  )
                order by
                  case
                    when codigo_barras=? or sku=? or codigo_interno=? then 0
                    when lower(nome)=lower(?) then 1
                    when lower(nome) like lower(?) then 2
                    else 3
                  end,
                  nome
                limit 40
                """,
                    codigo, codigo, codigo, "%" + codigo + "%",
                    codigo, codigo, codigo, codigo, codigo + "%");
            if (encontrados.isEmpty()) {
                msg("Produto nao encontrado. Confira codigo, descricao ou cadastro.");
                return;
            }
            Map<String, Object> p = encontrados.size() == 1 ? encontrados.get(0) : selecionarProdutoEncontrado(encontrados, codigo);
            if (p == null) {
                return;
            }
            // Quantidade default = 1 quando o operador deixa o campo em
            // branco ou em "0" (cenario tipico de leitor de codigo de
            // barras: scanner manda "code\n" sem o operador digitar qtd).
            BigDecimal qtd = money(qtdText);
            if (qtd.signum() <= 0) {
                qtd = BigDecimal.ONE;
            }
            BusinessRules.requirePositive(qtd, "Quantidade");
            BigDecimal preco = money(p.get("preco_venda").toString());
            BusinessRules.validateSalePrice(preco, user.autorizaPrecoZero || intValue(p.get("permite_preco_zero")) == 1);
            long produtoId = ((Number) p.get("id")).longValue();
            String nomeProduto = p.get("nome").toString();
            for (int i = 0; i < cart.size(); i++) {
                if (cart.get(i).produtoId == produtoId) {
                    CartItem existente = cart.get(i);
                    cart.set(i, new CartItem(produtoId, nomeProduto, existente.qtd.add(qtd), preco, existente.desconto()));
                    refreshCart();
                    return;
                }
            }
            cart.add(new CartItem(produtoId, nomeProduto, qtd, preco));
            refreshCart();
        } catch (Exception ex) { error(ex); }
    }

    private List<Map<String, Object>> buscarSugestoesProduto(String termo, int limite) throws Exception {
        return rows("""
                select nome, codigo_barras, sku, codigo_interno
                from produtos
                where ativo = 1
                  and (
                    codigo_barras like ?
                    or sku like ?
                    or codigo_interno like ?
                    or lower(nome) like lower(?)
                  )
                order by
                  case
                    when lower(nome)=lower(?) then 0
                    when lower(nome) like lower(?) then 1
                    else 2
                  end,
                  nome
                limit ?
                """,
                termo + "%", termo + "%", termo + "%", termo + "%",
                termo, termo + "%", Math.max(1, limite));
    }

    private List<Map<String, Object>> carregarCatalogoSugestoes() throws Exception {
        return rows("""
                select nome, codigo_barras, sku, codigo_interno
                from produtos
                where ativo = 1
                order by nome
                limit 5000
                """);
    }

    private boolean matchesSugestaoPrefix(Map<String, Object> p, String termoNorm) {
        if (termoNorm == null || termoNorm.isBlank()) {
            return false;
        }
        String nome = valueOrBlank(p.get("nome")).toLowerCase(Locale.ROOT);
        String barras = valueOrBlank(p.get("codigo_barras")).toLowerCase(Locale.ROOT);
        String sku = valueOrBlank(p.get("sku")).toLowerCase(Locale.ROOT);
        String interno = valueOrBlank(p.get("codigo_interno")).toLowerCase(Locale.ROOT);
        return nome.startsWith(termoNorm)
                || barras.startsWith(termoNorm)
                || sku.startsWith(termoNorm)
                || interno.startsWith(termoNorm);
    }

    private String tokenSugestaoProduto(Map<String, Object> p) {
        String barras = valueOrBlank(p.get("codigo_barras"));
        if (!barras.isBlank()) {
            return barras;
        }
        String sku = valueOrBlank(p.get("sku"));
        if (!sku.isBlank()) {
            return sku;
        }
        String interno = valueOrBlank(p.get("codigo_interno"));
        if (!interno.isBlank()) {
            return interno;
        }
        return valueOrBlank(p.get("nome"));
    }

    private String valueOrBlank(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /**
     * Abre uma janela de pesquisa de produtos: lista TODOS os produtos
     * ativos e filtra em tempo real conforme o operador digita parte do
     * nome / codigo. Ao escolher (Enter ou duplo clique), o produto NAO
     * vai direto pro carrinho: o sistema preenche o campo "Codigo /
     * Descricao" do PDV com o codigo de barras (ou SKU / interno / nome)
     * e fecha a janela. O operador ajusta a "Quantidade" se quiser e clica
     * em "Adicionar produto" para concluir.
     *
     * @param codigoBuscaPdv campo "Codigo / Descricao" do painel direito do PDV
     */
    private void abrirPesquisaProduto(JTextField codigoBuscaPdv) {
        try {
            requirePdvAccess();
            ensureCaixaAbertoParaVenda();
        } catch (Exception ex) {
            error(ex);
            return;
        }
        List<Map<String, Object>> produtos;
        try {
            produtos = rows("""
                    select id, nome, coalesce(codigo_barras,'') as codigo_barras,
                           coalesce(sku,'') as sku, coalesce(codigo_interno,'') as codigo_interno,
                           preco_venda, coalesce(estoque_atual, 0) as estoque_atual
                      from produtos
                     where ativo = 1
                     order by nome
                     limit 5000
                    """);
        } catch (Exception ex) {
            error(ex);
            return;
        }
        if (produtos.isEmpty()) {
            msg("Nenhum produto cadastrado. Cadastre produtos na aba 'Estoque' antes de vender.");
            return;
        }

        JDialog dialog = new JDialog(frame, "Pesquisar produto", true);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.getContentPane().setBackground(new Color(248, 248, 244));
        dialog.setSize(scale(900), scale(560));
        dialog.setLocationRelativeTo(frame);

        JPanel top = new JPanel(new BorderLayout(8, 4));
        top.setOpaque(false);
        top.setBorder(new EmptyBorder(10, 10, 4, 10));
        JLabel info = new JLabel("<html>Digite parte do nome ou codigo (" + produtos.size()
                + " produtos). Ao escolher, o item vai para o campo <b>Codigo / Descricao</b> do PDV; "
                + "ajuste a <b>Quantidade</b> e clique em <b>Adicionar produto</b>.</html>");
        info.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));
        info.setForeground(TEXT_MUTED);
        JTextField filtro = new JTextField();
        stylePdvField(filtro);
        filtro.setToolTipText("Digite parte do nome do produto (ex: 'cafe') ou codigo de barras / SKU.");
        top.add(info, BorderLayout.NORTH);
        top.add(filtro, BorderLayout.CENTER);

        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"ID", "Produto", "Codigo barras", "SKU", "Preco", "Estoque"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        Map<Integer, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> p : produtos) {
            int id = ((Number) p.get("id")).intValue();
            byId.put(id, p);
            String cb = String.valueOf(p.getOrDefault("codigo_barras", ""));
            String sku = String.valueOf(p.getOrDefault("sku", ""));
            model.addRow(new Object[]{
                    id,
                    p.get("nome"),
                    cb,
                    sku,
                    moneyText(money(String.valueOf(p.get("preco_venda")))),
                    p.get("estoque_atual")
            });
        }
        JTable table = new JTable(model);
        styleTable(table);
        table.setRowHeight(scale(28));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // Esconde a coluna ID (mantem so na model pra recuperar produto).
        table.getColumnModel().getColumn(0).setMinWidth(0);
        table.getColumnModel().getColumn(0).setMaxWidth(0);
        table.getColumnModel().getColumn(0).setWidth(0);
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        if (table.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }

        Runnable carregarSelecionadoNoPdv = () -> {
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) {
                if (table.getRowCount() == 0) return;
                viewRow = 0;
                table.setRowSelectionInterval(0, 0);
            }
            int modelRow = table.convertRowIndexToModel(viewRow);
            int id = ((Number) model.getValueAt(modelRow, 0)).intValue();
            Map<String, Object> p = byId.get(id);
            if (p == null) return;
            // Mesmo token que addCart usaria (barcode > sku > interno > nome).
            String cb = String.valueOf(p.getOrDefault("codigo_barras", "")).trim();
            String sku = String.valueOf(p.getOrDefault("sku", "")).trim();
            String ci = String.valueOf(p.getOrDefault("codigo_interno", "")).trim();
            String tokenAdd;
            if (!cb.isEmpty()) tokenAdd = cb;
            else if (!sku.isEmpty()) tokenAdd = sku;
            else if (!ci.isEmpty()) tokenAdd = ci;
            else tokenAdd = String.valueOf(p.get("nome"));
            dialog.dispose();
            codigoBuscaPdv.setText(tokenAdd);
            SwingUtilities.invokeLater(() -> {
                codigoBuscaPdv.requestFocusInWindow();
                codigoBuscaPdv.selectAll();
            });
        };

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    carregarSelecionadoNoPdv.run();
                }
            }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "loadPdv");
        table.getActionMap().put("loadPdv", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { carregarSelecionadoNoPdv.run(); }
        });

        // Filtro em tempo real: aplica regex case-insensitive sobre as
        // colunas Produto (1), Codigo barras (2) e SKU (3).
        DocumentListener filterListener = new DocumentListener() {
            private void apply() {
                String text = filtro.getText().trim();
                if (text.isBlank()) {
                    sorter.setRowFilter(null);
                } else {
                    sorter.setRowFilter(RowFilter.regexFilter(
                            "(?i)" + java.util.regex.Pattern.quote(text), 1, 2, 3));
                }
                if (table.getRowCount() > 0) {
                    table.setRowSelectionInterval(0, 0);
                }
            }
            @Override public void insertUpdate(DocumentEvent e) { apply(); }
            @Override public void removeUpdate(DocumentEvent e) { apply(); }
            @Override public void changedUpdate(DocumentEvent e) { apply(); }
        };
        filtro.getDocument().addDocumentListener(filterListener);
        // Down/Up no campo de texto navegam na tabela sem precisar tirar
        // foco do filtro (UX comum em buscas grandes).
        filtro.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent ev) {
                int code = ev.getKeyCode();
                if (code == KeyEvent.VK_DOWN || code == KeyEvent.VK_UP) {
                    int row = table.getSelectedRow();
                    int total = table.getRowCount();
                    if (total == 0) return;
                    int next = code == KeyEvent.VK_DOWN
                            ? Math.min(total - 1, row + 1)
                            : Math.max(0, row - 1);
                    table.setRowSelectionInterval(next, next);
                    table.scrollRectToVisible(table.getCellRect(next, 0, true));
                    ev.consume();
                } else if (code == KeyEvent.VK_ENTER) {
                    carregarSelecionadoNoPdv.run();
                    ev.consume();
                } else if (code == KeyEvent.VK_ESCAPE) {
                    dialog.dispose();
                    ev.consume();
                }
            }
        });

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        footer.setOpaque(false);
        JButton cancelar = button("Cancelar (Esc)");
        cancelar.addActionListener(e -> dialog.dispose());
        JButton selecionar = button("Carregar no PDV (Enter)");
        selecionar.setToolTipText("Preenche o campo Codigo / Descricao; ajuste a quantidade e clique em Adicionar produto.");
        selecionar.addActionListener(e -> carregarSelecionadoNoPdv.run());
        footer.add(cancelar);
        footer.add(selecionar);

        dialog.add(top, BorderLayout.NORTH);
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);
        dialog.add(footer, BorderLayout.SOUTH);
        SwingUtilities.invokeLater(filtro::requestFocusInWindow);
        dialog.setVisible(true);
    }

    private Map<String, Object> selecionarProdutoEncontrado(List<Map<String, Object>> produtos, String termo) {
        JDialog dialog = new JDialog(frame, "Selecionar produto: " + termo, true);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.getContentPane().setBackground(new Color(248, 248, 244));
        dialog.setSize(900, 500);
        dialog.setLocationRelativeTo(frame);

        JPanel top = new JPanel(new BorderLayout(8, 4));
        top.setOpaque(false);
        JLabel info = new JLabel("Foram encontrados " + produtos.size() + " produtos. Digite para filtrar e pressione Enter.");
        JTextField filtro = new JTextField(termo);
        stylePdvField(filtro);
        top.add(info, BorderLayout.NORTH);
        top.add(filtro, BorderLayout.CENTER);

        DefaultTableModel model = new DefaultTableModel(new Object[]{"ID", "Produto", "Codigo", "SKU", "Preco", "Estoque"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        Map<Integer, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> p : produtos) {
            int id = ((Number) p.get("id")).intValue();
            byId.put(id, p);
            model.addRow(new Object[]{
                    id,
                    p.get("nome"),
                    p.get("codigo_barras"),
                    p.get("sku"),
                    moneyText(money(String.valueOf(p.get("preco_venda")))),
                    p.get("estoque_atual")
            });
        }
        JTable table = new JTable(model);
        styleTable(table);
        table.setRowHeight(30);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        if (table.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }

        final Map<String, Object>[] selected = new Map[]{null};
        Runnable selectAction = () -> {
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) {
                return;
            }
            int modelRow = table.convertRowIndexToModel(viewRow);
            int id = ((Number) model.getValueAt(modelRow, 0)).intValue();
            selected[0] = byId.get(id);
            dialog.dispose();
        };
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    selectAction.run();
                }
            }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "choose");
        table.getActionMap().put("choose", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                selectAction.run();
            }
        });
        DocumentListener filterListener = new DocumentListener() {
            private void apply() {
                String text = filtro.getText().trim();
                if (text.isBlank()) {
                    sorter.setRowFilter(null);
                } else {
                    sorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text), 1, 2, 3));
                }
                if (table.getRowCount() > 0) {
                    table.setRowSelectionInterval(0, 0);
                }
            }
            @Override public void insertUpdate(DocumentEvent e) { apply(); }
            @Override public void removeUpdate(DocumentEvent e) { apply(); }
            @Override public void changedUpdate(DocumentEvent e) { apply(); }
        };
        filtro.getDocument().addDocumentListener(filterListener);
        filtro.addActionListener(e -> selectAction.run());

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        footer.setOpaque(false);
        JButton cancelar = button("Cancelar");
        cancelar.addActionListener(e -> dialog.dispose());
        JButton selecionar = button("Selecionar");
        selecionar.addActionListener(e -> selectAction.run());
        footer.add(cancelar);
        footer.add(selecionar);

        dialog.add(top, BorderLayout.NORTH);
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);
        dialog.add(footer, BorderLayout.SOUTH);
        SwingUtilities.invokeLater(() -> {
            filtro.requestFocusInWindow();
            filtro.selectAll();
        });
        dialog.setVisible(true);
        return selected[0];
    }

    private Item selecionarClienteFiado(String termoInicial) {
        try {
            List<Map<String, Object>> clientes = rows("""
                select id, nome, coalesce(cpf, '-') as cpf, coalesce(telefone, '-') as telefone
                from clientes
                where bloqueado = 0
                order by nome
                limit 300
                """);
            if (clientes.isEmpty()) {
                msg("Nenhum cliente ativo encontrado.");
                return null;
            }
            JDialog dialog = new JDialog(frame, "Selecionar cliente para convênio", true);
            dialog.setLayout(new BorderLayout(8, 8));
            dialog.getContentPane().setBackground(new Color(248, 248, 244));
            dialog.setSize(860, 460);
            dialog.setLocationRelativeTo(frame);

            JPanel top = new JPanel(new BorderLayout(8, 4));
            top.setOpaque(false);
            JLabel info = new JLabel("Digite nome, CPF ou telefone para filtrar.");
            JTextField filtro = new JTextField(termoInicial == null ? "" : termoInicial);
            stylePdvField(filtro);
            top.add(info, BorderLayout.NORTH);
            top.add(filtro, BorderLayout.CENTER);

            DefaultTableModel model = new DefaultTableModel(new Object[]{"ID", "Nome", "CPF", "Telefone"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            Map<Integer, Item> byId = new HashMap<>();
            for (Map<String, Object> c : clientes) {
                int id = ((Number) c.get("id")).intValue();
                String nome = String.valueOf(c.get("nome"));
                String cpf = String.valueOf(c.get("cpf"));
                String telefone = String.valueOf(c.get("telefone"));
                byId.put(id, new Item(id, nome));
                model.addRow(new Object[]{id, nome, cpf, telefone});
            }
            JTable table = new JTable(model);
            styleTable(table);
            table.setRowHeight(compactMode() ? 28 : 32);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
            table.setRowSorter(sorter);
            if (table.getRowCount() > 0) {
                table.setRowSelectionInterval(0, 0);
            }

            final Item[] selected = new Item[]{null};
            Runnable selectAction = () -> {
                int viewRow = table.getSelectedRow();
                if (viewRow < 0) {
                    return;
                }
                int modelRow = table.convertRowIndexToModel(viewRow);
                int id = ((Number) model.getValueAt(modelRow, 0)).intValue();
                selected[0] = byId.get(id);
                dialog.dispose();
            };
            table.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() >= 2) {
                        selectAction.run();
                    }
                }
            });

            DocumentListener filterListener = new DocumentListener() {
                private void apply() {
                    String text = filtro.getText();
                    if (text == null || text.isBlank()) {
                        sorter.setRowFilter(null);
                    } else {
                        String pattern = "(?i).*" + Pattern.quote(text.trim()) + ".*";
                        sorter.setRowFilter(RowFilter.regexFilter(pattern, 1, 2, 3));
                    }
                    if (table.getRowCount() > 0) {
                        table.setRowSelectionInterval(0, 0);
                    }
                }
                @Override public void insertUpdate(DocumentEvent e) { apply(); }
                @Override public void removeUpdate(DocumentEvent e) { apply(); }
                @Override public void changedUpdate(DocumentEvent e) { apply(); }
            };
            filtro.getDocument().addDocumentListener(filterListener);
            filtro.addActionListener(e -> selectAction.run());

            JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            footer.setOpaque(false);
            JButton cancelar = button("Cancelar");
            cancelar.addActionListener(e -> dialog.dispose());
            JButton selecionar = button("Selecionar");
            selecionar.addActionListener(e -> selectAction.run());
            footer.add(cancelar);
            footer.add(selecionar);

            dialog.add(top, BorderLayout.NORTH);
            dialog.add(new JScrollPane(table), BorderLayout.CENTER);
            dialog.add(footer, BorderLayout.SOUTH);
            SwingUtilities.invokeLater(() -> {
                filtro.requestFocusInWindow();
                filtro.selectAll();
            });
            dialog.setVisible(true);
            return selected[0];
        } catch (Exception ex) {
            error(ex);
            return null;
        }
    }

    private void ensureCaixaAbertoParaVenda() throws Exception {
        Item caixaSelecionado = (Item) caixaCombo.getSelectedItem();
        if (caixaSelecionado == null) {
            throw new AppException("Selecione um caixa.");
        }
        Map<String, Object> caixa = one("select status from caixas where id=?", caixaSelecionado.id);
        if (caixa == null || !"ABERTO".equals(String.valueOf(caixa.get("status")))) {
            throw new AppException("Abra o caixa antes de adicionar itens.");
        }
    }

    private BigDecimal cartSubtotalBrutoProdutos() {
        return cart.stream().map(CartItem::valorBrutoLinha).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal cartDescontosAcumulados() {
        return cart.stream().map(CartItem::desconto).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal cartTotalLiquido() {
        return cartSubtotalBrutoProdutos().subtract(cartDescontosAcumulados());
    }

    private void refreshCart() {
        cartModel.setRowCount(0);
        BigDecimal liquidos = BigDecimal.ZERO;
        for (CartItem item : cart) {
            BigDecimal liquido = item.valorLiquidoLinha();
            liquidos = liquidos.add(liquido);
            cartModel.addRow(new Object[]{
                    item.nome(), item.qtd(), moneyText(item.preco()),
                    moneyText(liquido)
            });
        }
        totalLabel.setText(moneyText(liquidos.max(BigDecimal.ZERO)));
        paymentFeedbackUpdater.run();
    }

    private void cancelarItemSelecionado(JTable cartTable) {
        try {
            requirePdvAccess();
            int selectedRow = cartTable.getSelectedRow();
            if (selectedRow < 0) {
                Toolkit.getDefaultToolkit().beep();
                return;
            }
            int modelIndex = cartTable.convertRowIndexToModel(selectedRow);
            if (modelIndex < 0 || modelIndex >= cart.size()) {
                Toolkit.getDefaultToolkit().beep();
                return;
            }
            cart.remove(modelIndex);
            refreshCart();
        } catch (Exception ex) {
            error(ex);
        }
    }

    /** Desconto em R$ apenas na linha selecionada do carrinho (atalho **F11**). */
    private void aplicarDescontoLinhaSelecionada(JTable cartTable) {
        try {
            requirePdvAccess();
            ensureCaixaAbertoParaVenda();
            int selectedRow = cartTable.getSelectedRow();
            if (selectedRow < 0) {
                msg("Selecione um item no carrinho para aplicar desconto.");
                return;
            }
            int modelIndex = cartTable.convertRowIndexToModel(selectedRow);
            if (modelIndex < 0 || modelIndex >= cart.size()) {
                Toolkit.getDefaultToolkit().beep();
                return;
            }
            CartItem atual = cart.get(modelIndex);
            BigDecimal linhaBruta = atual.valorBrutoLinha();
            JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
            form.setOpaque(false);
            form.add(label("Produto"));
            JLabel nomeProduto = new JLabel(atual.nome());
            nomeProduto.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));
            form.add(nomeProduto);
            form.add(label("Desconto (R$ nesta linha)"));
            JTextField campo = new JTextField(moneyInputText(atual.desconto()));
            stylePdvField(campo);
            bindMoneyMask(campo);
            form.add(campo);
            int ok = JOptionPane.showConfirmDialog(frame, form, "Desconto no item", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) {
                return;
            }
            BigDecimal novoDesc = safeMoneyText(campo.getText()).max(BigDecimal.ZERO).min(linhaBruta);
            BusinessRules.validateDiscount(linhaBruta, novoDesc, user.descontoMaximo());
            cart.set(modelIndex, new CartItem(atual.produtoId(), atual.nome(), atual.qtd(), atual.preco(), novoDesc));
            refreshCart();
        } catch (Exception ex) {
            error(ex);
        }
    }

    private void finalizarVenda(Map<String, BigDecimal> paymentInputs, String codigoValeTroca) {
        try {
            requirePdvAccess();
            if (cart.isEmpty()) {
                msg("Carrinho vazio.");
                return;
            }
            Item caixa = (Item) caixaCombo.getSelectedItem();
            BigDecimal subtotalBruto = cartSubtotalBrutoProdutos();
            BigDecimal desconto = cartDescontosAcumulados().max(BigDecimal.ZERO);
            if (desconto.compareTo(BigDecimal.ZERO) > 0) {
                BusinessRules.validateDiscount(subtotalBruto, desconto, user.descontoMaximo());
            }
            BigDecimal total = subtotalBruto.subtract(desconto);
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
                msg("Selecione um cliente quando houver valor em convênio.");
                return;
            }
            if (pagamentos.containsKey("CREDITO_TROCA") && (codigoValeTroca == null || codigoValeTroca.isBlank())) {
                msg("Informe o codigo do vale troca.");
                return;
            }
            // Bloqueio por LIMITE DE CONVENIO (FIADO).
            // Se a parcela em FIADO + saldo ja em aberto exceder o
            // "limite_credito" do cliente, oferecemos ao operador a
            // opcao de aumentar o limite (com senha de admin) ou
            // cancelar a venda. Sem isso a venda passaria mesmo
            // estourando o limite definido pelo dono.
            if (pagamentos.containsKey("FIADO") && clienteId != null) {
                if (!validarLimiteFiadoOuAumentar(clienteId, pagamentos.get("FIADO"))) {
                    return;
                }
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
            receiptService.generateForSale(vendaId);
            cart.clear();
            pdvAfterSaleCleanup.run();
            // Atualiza Fundo (dinheiro) e Cartao no topo do PDV apos cada
            // venda. Assim o operador ve em tempo real o saldo do caixa.
            pdvHeaderRefresher.run();
            // Se a venda usou Convenio (FIADO total ou parcial), pede pra
            // aba de Convenio recarregar suas tabelas. Assim que o caixa
            // troca de aba pra Convenio, ja ve a divida atualizada do
            // cliente sem precisar reabrir o sistema.
            if (pagamentos.containsKey("FIADO")) {
                convenioRefresher.run();
            }
            audit("VENDA", "Venda #" + vendaId + " | " + formaPagamento);
            msg("Venda realizada.");
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
            // Sangria/Suprimento nao alteram a coluna abertura_valor, mas o
            // saldo apresentado considera o fundo + dinheiro recebido. Ainda
            // assim atualizamos para refletir a operacao no UI.
            pdvHeaderRefresher.run();
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
            // Cancelar uma venda zera os pagamentos contabilizados; refresca
            // Fundo (dinheiro do dia) e Cartao no topo do PDV.
            pdvHeaderRefresher.run();
            convenioRefresher.run();
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
            abrirSelecaoCupomFiscal();
        } catch (Exception ex) { error(ex); }
    }

    /**
     * Abre uma janela com as vendas concluidas (filtraveis por dia/caixa) e mostra a previa dos itens
     * da venda selecionada. Ao confirmar, gera o cupom fiscal completo (com todos os itens) usando
     * {@link DesktopReceiptService#generateForSale(long)} — a "nota" emitida e a venda inteira, nao
     * por item.
     */
    private void abrirSelecaoCupomFiscal() throws Exception {
        JDialog dialog = new JDialog(frame, "Cupom fiscal — selecionar venda", true);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.getContentPane().setBackground(PANEL_BG);
        ((JComponent) dialog.getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel filtros = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filtros.setOpaque(false);
        filtros.add(label("Data"));
        SpinnerDateModel modeloData = new SpinnerDateModel(
                java.util.Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()),
                null, null, Calendar.DAY_OF_MONTH);
        JSpinner spData = new JSpinner(modeloData);
        spData.setEditor(new JSpinner.DateEditor(spData, "dd/MM/yyyy"));
        Dimension spPref = spData.getPreferredSize();
        spData.setPreferredSize(new Dimension(Math.min(scale(120), spPref.width + scale(8)), spPref.height));
        filtros.add(spData);
        filtros.add(label("Caixa"));
        JComboBox<Item> cbCaixa = new JComboBox<>();
        cbCaixa.addItem(new Item(-1, "Todos"));
        for (Map<String, Object> r : rows("select id, numero from caixas order by numero")) {
            cbCaixa.addItem(new Item(((Number) r.get("id")).longValue(), "Caixa " + r.get("numero")));
        }
        Item caixaSel = (Item) caixaCombo.getSelectedItem();
        if (caixaSel != null) {
            for (int i = 0; i < cbCaixa.getItemCount(); i++) {
                if (cbCaixa.getItemAt(i).id == caixaSel.id) {
                    cbCaixa.setSelectedIndex(i);
                    break;
                }
            }
        }
        filtros.add(cbCaixa);
        JButton btnAtualizar = button("Atualizar");
        filtros.add(btnAtualizar);
        dialog.add(filtros, BorderLayout.NORTH);

        DefaultTableModel modeloVendas = new DefaultTableModel(
                new Object[]{"Venda", "Data", "Caixa", "Operador", "Total", "Pagamento"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tabelaVendas = new JTable(modeloVendas);
        styleTable(tabelaVendas);
        tabelaVendas.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane spVendas = new JScrollPane(tabelaVendas);
        spVendas.setPreferredSize(new Dimension(scale(620), scale(360)));

        DefaultTableModel modeloItens = new DefaultTableModel(
                new Object[]{"Produto", "Qtd", "Unit.", "Subtotal"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tabelaItens = new JTable(modeloItens);
        styleTable(tabelaItens);
        JScrollPane spItens = new JScrollPane(tabelaItens);
        spItens.setPreferredSize(new Dimension(scale(440), scale(360)));

        JLabel lblResumo = new JLabel("Selecione uma venda para ver os itens.");
        lblResumo.setFont(new Font("Segoe UI", Font.BOLD, fontSize(13)));
        lblResumo.setBorder(new EmptyBorder(6, 4, 6, 4));

        JPanel direita = new JPanel(new BorderLayout(0, 6));
        direita.setOpaque(false);
        direita.add(lblResumo, BorderLayout.NORTH);
        direita.add(spItens, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, spVendas, direita);
        split.setResizeWeight(0.58);
        split.setBorder(BorderFactory.createEmptyBorder());
        dialog.add(split, BorderLayout.CENTER);

        JButton btnEmitir = button("Emitir cupom fiscal");
        JButton btnFechar = button("Fechar");
        JPanel acoes = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        acoes.setOpaque(false);
        acoes.add(btnFechar);
        acoes.add(btnEmitir);
        dialog.add(acoes, BorderLayout.SOUTH);

        Runnable carregarItens = () -> {
            modeloItens.setRowCount(0);
            int row = tabelaVendas.getSelectedRow();
            if (row < 0) {
                lblResumo.setText("Selecione uma venda para ver os itens.");
                return;
            }
            long vendaId = ((Number) modeloVendas.getValueAt(row, 0)).longValue();
            try {
                List<Map<String, Object>> itens = rows("""
                        select p.nome as nome, vi.quantidade as qtd, vi.preco_unitario as preco
                        from venda_itens vi
                        join produtos p on p.id = vi.produto_id
                        where vi.venda_id = ?
                        order by vi.id
                        """, vendaId);
                BigDecimal totalItens = BigDecimal.ZERO;
                int totalLinhas = 0;
                for (Map<String, Object> it : itens) {
                    BigDecimal qtd = money(it.get("qtd").toString());
                    BigDecimal preco = money(it.get("preco").toString());
                    BigDecimal sub = preco.multiply(qtd);
                    modeloItens.addRow(new Object[]{
                            it.get("nome"),
                            qtd.stripTrailingZeros().toPlainString(),
                            moneyText(preco),
                            moneyText(sub)
                    });
                    totalItens = totalItens.add(sub);
                    totalLinhas++;
                }
                lblResumo.setText("Venda #" + vendaId + " — " + totalLinhas + " item(ns) — Total itens: " + moneyText(totalItens));
            } catch (Exception ex) {
                lblResumo.setText("Erro ao carregar itens: " + ex.getMessage());
            }
        };

        Runnable carregarVendas = () -> {
            modeloVendas.setRowCount(0);
            modeloItens.setRowCount(0);
            lblResumo.setText("Selecione uma venda para ver os itens.");
            try {
                LocalDate dia = ((java.util.Date) spData.getValue()).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                Item cx = (Item) cbCaixa.getSelectedItem();
                String sql = """
                        select v.id, v.timestamp, c.numero as caixa, u.nome as operador, v.total, v.forma_pagamento
                        from vendas v
                        join caixas c on c.id = v.caixa_id
                        join usuarios u on u.id = v.operador_id
                        where v.status = 'CONCLUIDA' and date(v.timestamp) = date(?)
                        """ + (cx == null || cx.id < 0 ? "" : " and v.caixa_id = ? ")
                        + " order by v.id desc";
                List<Map<String, Object>> data = (cx == null || cx.id < 0)
                        ? rows(sql, dia.toString())
                        : rows(sql, dia.toString(), cx.id);
                for (Map<String, Object> r : data) {
                    LocalDateTime ts = LocalDateTime.parse(r.get("timestamp").toString());
                    modeloVendas.addRow(new Object[]{
                            ((Number) r.get("id")).longValue(),
                            ts.format(BR_DATE_TIME),
                            "Caixa " + r.get("caixa"),
                            r.get("operador"),
                            moneyText(money(r.get("total").toString())),
                            r.get("forma_pagamento")
                    });
                }
                if (modeloVendas.getRowCount() > 0) {
                    tabelaVendas.setRowSelectionInterval(0, 0);
                }
            } catch (Exception ex) {
                error(ex);
            }
        };

        tabelaVendas.getSelectionModel().addListSelectionListener(ev -> {
            if (!ev.getValueIsAdjusting()) {
                carregarItens.run();
            }
        });
        spData.addChangeListener(e -> carregarVendas.run());
        cbCaixa.addActionListener(e -> carregarVendas.run());
        btnAtualizar.addActionListener(e -> carregarVendas.run());
        btnFechar.addActionListener(e -> dialog.dispose());
        btnEmitir.addActionListener(e -> {
            int row = tabelaVendas.getSelectedRow();
            if (row < 0) {
                msg("Selecione uma venda na lista.");
                return;
            }
            long vendaId = ((Number) modeloVendas.getValueAt(row, 0)).longValue();
            try {
                receiptService.generateForSale(vendaId);
                audit("REEMITIR_COMPROVANTE", "Venda #" + vendaId);
                msg("Cupom fiscal emitido para a venda #" + vendaId + ".");
                dialog.dispose();
            } catch (Exception ex) {
                error(ex);
            }
        });

        carregarVendas.run();
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
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

    private void alterarClienteFiado(JTable tabelaClientes) {
        try {
            requireFiadoAccess();
            int viewRow = tabelaClientes.getSelectedRow();
            if (viewRow < 0) {
                msg("Selecione um cliente na tabela para alterar.");
                return;
            }
            int modelRow = tabelaClientes.convertRowIndexToModel(viewRow);
            DefaultTableModel model = (DefaultTableModel) tabelaClientes.getModel();
            long clienteId = ((Number) model.getValueAt(modelRow, 0)).longValue();
            String nomeCliente = String.valueOf(model.getValueAt(modelRow, 1));
            ClienteEditDialog dialog = new ClienteEditDialog(frame, con, clienteId, () -> {
                try {
                    audit("CLIENTE_EDITADO", "Cliente #" + clienteId + " " + nomeCliente);
                } catch (Exception ignored) {
                    // Auditoria nao deve quebrar o fluxo de edicao.
                }
                // Refresh leve: so recarrega as tabelas da aba Convenio
                // (em vez de reconstruir toda a janela com refreshFrame).
                convenioRefresher.run();
            });
            dialog.setVisible(true);
        } catch (Exception ex) {
            error(ex);
        }
    }

    private void excluirClienteFiado(JTable tabelaClientes) {
        try {
            requireFiadoAccess();
            int viewRow = tabelaClientes.getSelectedRow();
            if (viewRow < 0) {
                msg("Selecione um cliente na tabela.");
                return;
            }
            int modelRow = tabelaClientes.convertRowIndexToModel(viewRow);
            DefaultTableModel model = (DefaultTableModel) tabelaClientes.getModel();
            long clienteId = ((Number) model.getValueAt(modelRow, 0)).longValue();
            String nomeCliente = String.valueOf(model.getValueAt(modelRow, 1));
            int confirma = JOptionPane.showConfirmDialog(
                    frame,
                    "Remover o cliente \"" + nomeCliente + "\" da lista?\n"
                            + "(Nao e possivel se houver convênio em aberto.)",
                    "Excluir cliente",
                    JOptionPane.YES_NO_OPTION
            );
            if (confirma != JOptionPane.YES_OPTION) {
                return;
            }
            Map<String, Object> aberto = one("""
                    select coalesce(sum(valor - valor_pago), 0) as aberto
                    from fiado where cliente_id = ? and status = 'ABERTO'
                    """, clienteId);
            BigDecimal emAberto = aberto == null ? BigDecimal.ZERO : money(String.valueOf(aberto.get("aberto")));
            if (emAberto.compareTo(BigDecimal.ZERO) > 0) {
                throw new AppException("Este cliente possui convênio em aberto (" + moneyText(emAberto)
                        + "). Quite o saldo antes de remover.");
            }
            update("update clientes set bloqueado = 1 where id = ?", clienteId);
            audit("CLIENTE_EXCLUIDO", "Cliente #" + clienteId + " " + nomeCliente);
            msg("Cliente removido da lista.");
            convenioRefresher.run();
        } catch (Exception ex) {
            error(ex);
        }
    }

    private void pagarFiado() {
        try {
            requireFiadoAccess();
            String id = JOptionPane.showInputDialog(frame, "ID do lançamento em convênio (coluna ID da lista acima):");
            String valor = JOptionPane.showInputDialog(frame, "Valor pago:");
            BigDecimal valorPago = money(valor);
            BusinessRules.requirePositive(valorPago, "Valor pago");
            update("insert into fiado_pagamentos (fiado_id, valor, data, operador_id) values (?, ?, ?, ?)",
                    Long.parseLong(id), valorPago, LocalDateTime.now().toString(), user.id);
            update("update fiado set valor_pago=valor_pago+? where id=?", valorPago, Long.parseLong(id));
            update("update fiado set status='PAGO' where id=? and valor_pago >= valor", Long.parseLong(id));
            convenioRefresher.run();
        } catch (Exception ex) { error(ex); }
    }

    /**
     * Pequeno dialogo de busca inteligente para escolher o cliente que
     * tera baixa no convenio.
     *
     * <p>Funciona igual ao autocomplete do PDV: o operador digita parte
     * do nome (ex: "g" lista todos que comecam com G), CPF ou telefone,
     * e a lista mostra ate 12 clientes com o saldo em aberto de cada um.
     * Setas + Enter ou clique para confirmar.</p>
     *
     * @return array {@code [clienteId]} com o id do cliente escolhido,
     *         ou {@code null} se o operador cancelar.
     */
    private long[] selecionarClienteParaBaixaConvenio() {
        final long[] resultado = {-1L};
        JDialog dialog = new JDialog(frame, "\uD83D\uDD0D  Selecionar cliente para dar baixa", true);
        dialog.setLayout(new BorderLayout(0, 0));
        dialog.getContentPane().setBackground(new Color(248, 248, 244));

        // Cabecalho explicativo.
        JLabel info = new JLabel(
                "<html>Digite parte do <b>nome</b>, <b>CPF</b> ou <b>telefone</b> "
                + "do cliente. Use \u2191/\u2193 e Enter para escolher.</html>");
        info.setBorder(new EmptyBorder(10, 12, 6, 12));
        info.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));

        JTextField busca = new JTextField();
        stylePdvField(busca);
        busca.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 1, 1, 1, MARKET_GREEN),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));

        DefaultListModel<Item> sugestoesModel = new DefaultListModel<>();
        JList<Item> sugestoesList = new JList<>(sugestoesModel);
        sugestoesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sugestoesList.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(13)));
        sugestoesList.setFixedCellHeight(compactMode() ? 24 : 28);
        JScrollPane scroll = new JScrollPane(sugestoesList);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(220, 220, 220)));

        JLabel rodape = new JLabel(" ");
        rodape.setForeground(TEXT_MUTED);
        rodape.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
        rodape.setBorder(new EmptyBorder(4, 12, 8, 12));

        JButton cancelar = button("Cancelar");
        JButton confirmar = button("\u2705  Continuar");
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        footer.setOpaque(false);
        footer.add(cancelar);
        footer.add(confirmar);

        JPanel topo = new JPanel(new BorderLayout());
        topo.setOpaque(false);
        topo.setBorder(new EmptyBorder(0, 12, 6, 12));
        topo.add(busca, BorderLayout.CENTER);

        JPanel corpo = new JPanel(new BorderLayout());
        corpo.setOpaque(false);
        corpo.add(info, BorderLayout.NORTH);
        corpo.add(topo, BorderLayout.CENTER);

        JPanel meio = new JPanel(new BorderLayout());
        meio.setOpaque(false);
        meio.add(scroll, BorderLayout.CENTER);
        meio.add(rodape, BorderLayout.SOUTH);
        meio.setBorder(new EmptyBorder(0, 12, 0, 12));

        dialog.add(corpo, BorderLayout.NORTH);
        dialog.add(meio, BorderLayout.CENTER);
        dialog.add(footer, BorderLayout.SOUTH);

        // Busca inteligente: prefixo em nome/cpf/telefone + saldo em aberto.
        Runnable atualizar = () -> {
            String termo = busca.getText() == null ? "" : busca.getText().trim();
            sugestoesModel.clear();
            try {
                List<Map<String, Object>> linhas;
                if (termo.isEmpty()) {
                    // Sem termo: mostra os clientes com saldo em aberto
                    // (mais provaveis de receber baixa).
                    linhas = rows("""
                            select c.id, c.nome,
                                   coalesce(c.cpf,'-') as cpf,
                                   coalesce(sum(f.valor - f.valor_pago), 0) as saldo
                              from clientes c
                              left join fiado f on f.cliente_id = c.id and f.status = 'ABERTO'
                             where c.bloqueado = 0
                             group by c.id
                             having saldo > 0
                             order by saldo desc, c.nome
                             limit 12
                            """);
                } else {
                    String like = termo.toLowerCase(Locale.ROOT) + "%";
                    linhas = rows("""
                            select c.id, c.nome,
                                   coalesce(c.cpf,'-') as cpf,
                                   coalesce(sum(f.valor - f.valor_pago), 0) as saldo
                              from clientes c
                              left join fiado f on f.cliente_id = c.id and f.status = 'ABERTO'
                             where c.bloqueado = 0
                               and (lower(c.nome) like ?
                                    or replace(coalesce(c.cpf,''), '.', '') like ?
                                    or replace(coalesce(c.telefone,''), ' ', '') like ?)
                             group by c.id
                             order by c.nome
                             limit 12
                            """, like, termo + "%", termo + "%");
                }
                for (Map<String, Object> linha : linhas) {
                    long id = ((Number) linha.get("id")).longValue();
                    String nome = String.valueOf(linha.get("nome"));
                    String cpf = String.valueOf(linha.get("cpf"));
                    BigDecimal saldo = money(String.valueOf(linha.get("saldo")));
                    String label = nome + "  -  CPF " + cpf
                            + "  -  Em aberto " + moneyText(saldo);
                    sugestoesModel.addElement(new Item(id, label));
                }
            } catch (Exception ignored) {
                // Falha de query nao deve quebrar o dialogo.
            }
            if (!sugestoesModel.isEmpty()) {
                sugestoesList.setSelectedIndex(0);
                sugestoesList.ensureIndexIsVisible(0);
            }
            int qtd = sugestoesModel.size();
            if (termo.isEmpty()) {
                rodape.setText(qtd == 0
                        ? "Nenhum cliente com convenio em aberto."
                        : qtd + " cliente(s) com saldo em aberto. Digite para refinar.");
            } else {
                rodape.setText(qtd == 0
                        ? "Nenhum resultado para \"" + termo + "\"."
                        : qtd + " resultado(s) para \"" + termo + "\".");
            }
        };

        Runnable confirmarSelecao = () -> {
            int idx = sugestoesList.getSelectedIndex();
            if (idx < 0 && !sugestoesModel.isEmpty()) idx = 0;
            if (idx < 0) {
                rodape.setText("Nenhum cliente para selecionar.");
                return;
            }
            resultado[0] = sugestoesModel.get(idx).id();
            dialog.dispose();
        };

        busca.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { atualizar.run(); }
            @Override public void removeUpdate(DocumentEvent e) { atualizar.run(); }
            @Override public void changedUpdate(DocumentEvent e) { atualizar.run(); }
        });
        busca.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent ev) {
                int code = ev.getKeyCode();
                if (sugestoesModel.isEmpty()) {
                    if (code == KeyEvent.VK_ESCAPE) dialog.dispose();
                    return;
                }
                if (code == KeyEvent.VK_DOWN) {
                    int next = Math.min(sugestoesList.getSelectedIndex() + 1,
                            sugestoesModel.size() - 1);
                    sugestoesList.setSelectedIndex(Math.max(0, next));
                    sugestoesList.ensureIndexIsVisible(Math.max(0, next));
                    ev.consume();
                } else if (code == KeyEvent.VK_UP) {
                    int prev = Math.max(0, sugestoesList.getSelectedIndex() - 1);
                    sugestoesList.setSelectedIndex(prev);
                    sugestoesList.ensureIndexIsVisible(prev);
                    ev.consume();
                } else if (code == KeyEvent.VK_ENTER) {
                    confirmarSelecao.run();
                    ev.consume();
                } else if (code == KeyEvent.VK_ESCAPE) {
                    dialog.dispose();
                    ev.consume();
                }
            }
        });
        sugestoesList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent ev) {
                int idx = sugestoesList.locationToIndex(ev.getPoint());
                if (idx >= 0) {
                    sugestoesList.setSelectedIndex(idx);
                    if (ev.getClickCount() >= 2) {
                        confirmarSelecao.run();
                    }
                }
            }
        });
        confirmar.addActionListener(e -> confirmarSelecao.run());
        cancelar.addActionListener(e -> dialog.dispose());

        atualizar.run();

        dialog.setSize(560, 420);
        dialog.setLocationRelativeTo(frame);
        SwingUtilities.invokeLater(busca::requestFocusInWindow);
        dialog.setVisible(true);

        return resultado[0] < 0 ? null : new long[]{resultado[0]};
    }

    /**
     * Abre o fluxo de "Dar baixa no convenio".
     *
     * <p>Etapa 1: pequeno dialogo com BUSCA INTELIGENTE (autocomplete).
     * Operador digita parte do nome ou CPF; aparece a lista filtrada
     * em tempo real, com o saldo aberto de cada cliente. Setas + Enter
     * ou clique para confirmar.</p>
     *
     * <p>Etapa 2: dialogo de pagamento (valor + forma) para o cliente
     * escolhido. Distribui FIFO entre os lancamentos em aberto.</p>
     */
    private void darBaixaConvenio() {
        try {
            requireFiadoAccess();
            // -------- Etapa 1: busca inteligente do cliente --------
            long[] selecionado = selecionarClienteParaBaixaConvenio();
            if (selecionado == null) {
                return; // operador cancelou
            }
            long clienteId = selecionado[0];
            // Buscar nome canonico do cliente do banco.
            Map<String, Object> info = one(
                    "select nome from clientes where id = ?", clienteId);
            String nomeCliente = info == null ? ("#" + clienteId)
                    : String.valueOf(info.get("nome"));

            // Saldo total em aberto desse cliente (soma dos lancamentos ABERTO).
            Map<String, Object> saldoRow = one("""
                    select coalesce(sum(valor - valor_pago), 0) as aberto,
                           count(*) as qtd
                      from fiado
                     where cliente_id = ? and status = 'ABERTO'
                    """, clienteId);
            BigDecimal saldoAberto = saldoRow == null
                    ? BigDecimal.ZERO
                    : money(String.valueOf(saldoRow.get("aberto")));
            int qtdLancamentos = saldoRow == null ? 0
                    : ((Number) saldoRow.get("qtd")).intValue();

            if (saldoAberto.signum() <= 0) {
                msg("Cliente \"" + nomeCliente + "\" nao possui convênio em aberto.");
                return;
            }

            // Dialogo de baixa: saldo (read-only), valor a pagar (default = saldo),
            // forma de pagamento.
            JTextField campoValor = new JTextField(moneyText(saldoAberto));
            bindMoneyMask(campoValor);
            JComboBox<String> formaPg = new JComboBox<>(
                    new String[]{"DINHEIRO", "PIX", "DEBITO", "CREDITO"});

            JPanel formBaixa = new JPanel(new GridLayout(0, 2, 8, 6));
            formBaixa.add(label("Cliente"));
            JLabel lblNome = new JLabel(nomeCliente + "  (#" + clienteId + ")");
            lblNome.setFont(new Font("Segoe UI", Font.BOLD, fontSize(13)));
            formBaixa.add(lblNome);
            formBaixa.add(label("Saldo em aberto"));
            JLabel lblSaldo = new JLabel(moneyText(saldoAberto)
                    + "   (" + qtdLancamentos + " lan\u00e7amento"
                    + (qtdLancamentos == 1 ? "" : "s") + ")");
            lblSaldo.setForeground(new Color(0xC6, 0x28, 0x28));
            lblSaldo.setFont(new Font("Segoe UI", Font.BOLD, fontSize(13)));
            formBaixa.add(lblSaldo);
            formBaixa.add(label("Valor a pagar"));
            formBaixa.add(campoValor);
            formBaixa.add(label("Forma de pagamento"));
            formBaixa.add(formaPg);

            JLabel ajuda = new JLabel(
                    "<html><i>O valor sera distribu\u00eddo automaticamente entre os "
                    + "lan\u00e7amentos em aberto, come\u00e7ando pelos mais antigos.</i></html>");
            ajuda.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
            ajuda.setForeground(new Color(0x5D, 0x40, 0x37));

            JPanel container = new JPanel(new BorderLayout(0, 8));
            container.add(formBaixa, BorderLayout.CENTER);
            container.add(ajuda, BorderLayout.SOUTH);
            container.setPreferredSize(new Dimension(420, 180));

            int opt = JOptionPane.showConfirmDialog(
                    frame, container,
                    "\uD83D\uDCB5  Dar baixa no conv\u00eanio",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (opt != JOptionPane.OK_OPTION) {
                return;
            }

            BigDecimal valorPago = money(campoValor.getText());
            BusinessRules.requirePositive(valorPago, "Valor a pagar");
            if (valorPago.compareTo(saldoAberto) > 0) {
                throw new AppException("O valor (" + moneyText(valorPago)
                        + ") nao pode ser maior que o saldo em aberto ("
                        + moneyText(saldoAberto) + ").");
            }
            String forma = String.valueOf(formaPg.getSelectedItem());

            // Distribui o pagamento FIFO entre os lancamentos em aberto.
            List<Map<String, Object>> abertos = rows("""
                    select id, valor, valor_pago
                      from fiado
                     where cliente_id = ? and status = 'ABERTO'
                     order by data_criacao asc, id asc
                    """, clienteId);

            BigDecimal restante = valorPago;
            String hoje = LocalDateTime.now().toString();
            int lancamentosAfetados = 0;
            int lancamentosQuitados = 0;
            for (Map<String, Object> linha : abertos) {
                if (restante.signum() <= 0) break;
                long fiadoId = ((Number) linha.get("id")).longValue();
                BigDecimal valor = money(String.valueOf(linha.get("valor")));
                BigDecimal jaPago = money(String.valueOf(linha.get("valor_pago")));
                BigDecimal aberto = valor.subtract(jaPago).max(BigDecimal.ZERO);
                if (aberto.signum() <= 0) continue;
                BigDecimal aplicar = aberto.min(restante);

                update("insert into fiado_pagamentos (fiado_id, valor, data, operador_id) "
                        + "values (?, ?, ?, ?)",
                        fiadoId, aplicar, hoje, user.id);
                update("update fiado set valor_pago = valor_pago + ? where id = ?",
                        aplicar, fiadoId);
                update("update fiado set status = 'PAGO' where id = ? "
                        + "and valor_pago >= valor", fiadoId);

                restante = restante.subtract(aplicar);
                lancamentosAfetados++;
                if (aplicar.compareTo(aberto) >= 0) {
                    lancamentosQuitados++;
                }
            }

            BigDecimal novoSaldo = saldoAberto.subtract(valorPago).max(BigDecimal.ZERO);
            audit("BAIXA_CONVENIO",
                    "Cliente #" + clienteId + " " + nomeCliente
                    + " | Pago " + moneyText(valorPago)
                    + " (" + forma + ")"
                    + " | " + lancamentosAfetados + " lanc. afetado(s)"
                    + ", " + lancamentosQuitados + " quitado(s)"
                    + " | Saldo restante " + moneyText(novoSaldo));

            String resumo = "Baixa registrada para \"" + nomeCliente + "\".\n"
                    + "Pago: " + moneyText(valorPago) + " (" + forma + ")\n"
                    + "Lan\u00e7amento(s) afetado(s): " + lancamentosAfetados
                    + "  |  Quitado(s): " + lancamentosQuitados + "\n"
                    + "Saldo restante: " + moneyText(novoSaldo);
            JOptionPane.showMessageDialog(frame, resumo,
                    "Baixa no conv\u00eanio",
                    JOptionPane.INFORMATION_MESSAGE);

            convenioRefresher.run();
        } catch (Exception ex) {
            error(ex);
        }
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
            // Modelo READ-ONLY: as celulas exibidas nao podem ser editadas
            // diretamente (sem duplo clique transformando a celula em campo
            // de texto). Toda alteracao de dado deve passar pelos botoes /
            // dialogos dedicados (ex: "Alterar cliente selecionado"). Isso
            // evita edicoes acidentais e mantem a regra: cadastro pelo form
            // + alteracao pelo dialogo + tabela apenas para visualizar.
            DefaultTableModel model = new DefaultTableModel(lines, cols) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            JTable table = new JTable(model);
            table.setAutoCreateRowSorter(true);
            // Garante que nao da pra disparar editor por nenhum atalho.
            table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
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
        panel.setBorder(BorderFactory.createEmptyBorder(compactMode() ? 8 : 18, compactMode() ? 8 : 18, compactMode() ? 8 : 18, compactMode() ? 8 : 18));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private JPanel section(String title, Component child) {
        return section(title, child, false);
    }

    private JPanel section(String title, Component child, boolean compactReport) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(PANEL_BG);
        // Card branco com borda fina cinza (estilo card-header-green dos prints).
        panel.setBorder(BorderFactory.createLineBorder(BORDER_SOFT));

        // Header VERDE ESCURO com titulo BRANCO e icone (igual aos prints).
        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setBackground(MARKET_GREEN);
        int padHV = compactReport ? 8 : (compactMode() ? 10 : 12);
        int padHH = compactReport ? 10 : (compactMode() ? 12 : 16);
        head.setBorder(new EmptyBorder(padHV, padHH, padHV, padHH));

        // Lado esquerdo: icone (Segoe UI Emoji) + titulo branco
        JPanel headLeft = new JPanel();
        headLeft.setOpaque(false);
        headLeft.setLayout(new BoxLayout(headLeft, BoxLayout.X_AXIS));
        String icon = iconForTitle(title);
        if (!icon.isEmpty()) {
            JLabel iconLbl = new JLabel(icon);
            iconLbl.setFont(new Font("Segoe UI Emoji", Font.PLAIN,
                    fontSize(compactReport ? 14 : (compactMode() ? 14 : 16))));
            iconLbl.setForeground(Color.WHITE);
            headLeft.add(iconLbl);
            headLeft.add(Box.createHorizontalStrut(8));
        }
        JLabel heading = new JLabel(title);
        int fSize = compactReport ? fontSize(compactMode() ? 12 : 13) : fontSize(compactMode() ? 13 : 15);
        heading.setFont(new Font("Segoe UI", Font.BOLD, fSize));
        heading.setForeground(Color.WHITE);
        headLeft.add(heading);
        head.add(headLeft, BorderLayout.WEST);
        panel.add(head, BorderLayout.NORTH);

        // Corpo branco com padding generoso.
        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(PANEL_BG);
        int padB = compactReport ? 6 : (compactMode() ? 10 : 14);
        body.setBorder(new EmptyBorder(padB, padB, padB, padB));
        if (child instanceof JTable table) {
            styleTable(table);
            if (compactReport) {
                table.setRowHeight(compactMode() ? 22 : 26);
            }
            JScrollPane sp = new JScrollPane(table);
            sp.setBorder(BorderFactory.createLineBorder(BORDER_SOFT));
            sp.getViewport().setBackground(PANEL_BG);
            body.add(sp, BorderLayout.CENTER);
        } else if (child instanceof JPanel cp && cp.getLayout() instanceof GridBagLayout) {
            // Forms compactos (GridBagLayout com addCompactRow): alinha
            // a esquerda pra nao esticar os campos a largura toda do card.
            JPanel westWrap = new JPanel(new BorderLayout());
            westWrap.setOpaque(false);
            westWrap.add(child, BorderLayout.WEST);
            body.add(westWrap, BorderLayout.CENTER);
        } else {
            // Demais conteudos (drop zones, cards mistos, etc) ocupam
            // toda a largura disponivel.
            body.add(child, BorderLayout.CENTER);
        }
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    /** Mapeia titulos comuns para icones unicode (Segoe UI Emoji) usados no header dos cards. */
    private static String iconForTitle(String title) {
        if (title == null) return "";
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("itens da venda") || t.contains("carrinho")) return "\uD83D\uDED2"; // shopping cart
        if (t.contains("alertas") || t.contains("alerta")) return "\u26A0\uFE0F";          // warning
        if (t.contains("vendas recentes")) return "\uD83D\uDCC8";                          // chart up
        if (t.contains("importar xml") || t.contains("importar nf")) return "\uD83D\uDCE4";// outbox / upload
        if (t.contains("nf-e") || t.contains("nota fiscal")) return "\uD83D\uDCC4";        // page facing up
        if (t.contains("cadastrar produto") || t.contains("produtos cadastrados")
                || t.contains("estoque")) return "\uD83D\uDCE6";                            // package
        if (t.contains("cadastrar fornec") || t.contains("fornecedores")) return "\uD83D\uDE9A"; // truck
        if (t.contains("cadastrar cliente") || t.contains("clientes")) return "\uD83D\uDC64";    // bust in silhouette
        if (t.contains("movimenta") || t.contains("financ")) return "\uD83D\uDCB2";        // dollar sign
        if (t.contains("gerar relat") || t.contains("relatorios disp") || t.contains("relat\u00f3rios disp")
                || t.contains("relatorios") || t.contains("relat\u00f3rios")) return "\uD83D\uDCCA"; // bar chart
        if (t.contains("forma de pagamento") || t.contains("pagamento")) return "\uD83D\uDCB3";  // credit card
        if (t.contains("total da compra") || t.contains("total")) return "\uD83D\uDCB0";          // money bag
        if (t.contains("caixa") || t.contains("status dos caixas")) return "\uD83D\uDCBB";        // computer
        if (t.contains("vencendo")) return "\u23F0";                                              // alarm clock
        if (t.contains("fiado")) return "\uD83D\uDCB3";                                            // credit card
        return "";
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

    private BigDecimal safeMoneyText(String text) {
        try {
            return money(text);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private void bindPdvShortcuts(JComponent root, JTextField codigo, JButton limpar, JButton finalizar,
                                  JButton cancelarItem, JButton comprovante, JTable tabelaCarrinho) {
        Runnable descontoLinha = () -> aplicarDescontoLinhaSelecionada(tabelaCarrinho);
        bindShortcut(root, "focusCodigo", KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), codigo::requestFocusInWindow);
        bindShortcut(root, "finalizarVenda", KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), finalizar::doClick);
        bindShortcut(root, "limparCarrinho", KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), limpar::doClick);
        bindShortcut(root, "cancelarItem", KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), cancelarItem::doClick);
        bindShortcut(root, "reemitirComprovante", KeyStroke.getKeyStroke(KeyEvent.VK_F10, 0), comprovante::doClick);
        bindShortcut(root, "descontoLinha", KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), descontoLinha);
        bindShortcut(root, "limparCodigo", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), () -> {
            codigo.setText("");
            codigo.requestFocusInWindow();
        });
        bindShortcut(tabelaCarrinho, "descontoLinhaTabela", KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), descontoLinha);
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
        labels.put("fiado", "Convênio gerado");
        labels.put("devolucao_dinheiro", "Devolucao em dinheiro");
        labels.put("devolucao_pix", "Devolucao em PIX");
        labels.put("devolucao_debito", "Devolucao em debito");
        labels.put("devolucao_credito", "Devolucao em credito");
        labels.put("abate_fiado", "Abate em convênio");
        labels.put("vale_troca_emitido", "Vale troca emitido");
        labels.put("recebimentos_fiado", "Recebimentos de convênio");
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
        label.setFont(new Font("Segoe UI", Font.BOLD, fontSize(26)));
        label.setForeground(MARKET_GREEN);
        label.setBorder(new EmptyBorder(0, 0, 12, 0));
        return label;
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.BOLD, fontSize(12)));
        label.setForeground(TEXT_DARK);
        return label;
    }

    /**
     * Limita a largura do campo ao tamanho util do texto (sem esticar ate
     * a borda direita da janela). {@code columns} segue a convencao do
     * Swing ({@link JTextField#setColumns(int)}).
     */
    private void limitFieldWidth(JComponent c, int columns) {
        int cols = Math.max(1, columns);
        if (c instanceof JTextField tf) {
            tf.setColumns(cols);
            Dimension d = tf.getPreferredSize();
            tf.setMinimumSize(d);
            tf.setPreferredSize(d);
            tf.setMaximumSize(new Dimension(d.width, d.height));
        } else if (c instanceof JComboBox<?> cb) {
            Dimension d = cb.getPreferredSize();
            int cap = Math.min(22 * cols + 48, 260);
            int w = Math.min(Math.max(d.width + 8, 96), cap);
            Dimension fixed = new Dimension(w, d.height);
            cb.setPreferredSize(fixed);
            cb.setMaximumSize(fixed);
            cb.setMinimumSize(new Dimension(Math.min(80, w), d.height));
        }
    }

    /**
     * Linha com dois pares label+campo; {@code cols1/cols2} definem a
     * largura aproximada dos campos (caracteres). Uma coluna extra absorve
     * o espaco sobrando pra direita (campos nao ocupam a tela inteira).
     */
    private void addCompactRow(JPanel form, int row,
                               String label1, JComponent field1, int cols1,
                               String label2, JComponent field2, int cols2) {
        limitFieldWidth(field1, cols1);
        limitFieldWidth(field2, cols2);
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = row;
        gc.insets = new Insets(3, 4, 3, 6);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.NONE;

        gc.gridx = 0;
        gc.weightx = 0;
        form.add(label(label1), gc);

        gc.gridx = 1;
        gc.weightx = 0;
        form.add(field1, gc);

        gc.gridx = 2;
        gc.weightx = 0;
        gc.insets = new Insets(3, 14, 3, 6);
        form.add(label(label2), gc);

        gc.gridx = 3;
        gc.weightx = 0;
        gc.insets = new Insets(3, 4, 3, 6);
        form.add(field2, gc);

        gc.gridx = 4;
        gc.weightx = 1;
        gc.gridwidth = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(0, 0, 0, 0);
        form.add(Box.createHorizontalGlue(), gc);
    }

    /** Uma linha com um unico campo (ex.: Observacao em linha inteira). */
    private void addCompactRow(JPanel form, int row,
                               String label1, JComponent field1, int cols1) {
        limitFieldWidth(field1, cols1);
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = row;
        gc.insets = new Insets(3, 4, 3, 6);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.NONE;

        gc.gridx = 0;
        gc.weightx = 0;
        form.add(label(label1), gc);

        gc.gridx = 1;
        gc.weightx = 0;
        gc.gridwidth = 3;
        form.add(field1, gc);
        gc.gridwidth = 1;

        gc.gridx = 4;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(0, 0, 0, 0);
        form.add(Box.createHorizontalGlue(), gc);
    }

    /** Linhas do resumo (subtotal/total/recebido/troco) no PDV: fonte maior para leitura no caixa. */
    private void stylePdvResumoLinha(JLabel l, boolean destaqueTotal) {
        int base = destaqueTotal ? 22 : 16;
        int sz = compactMode() ? Math.max(14, base - 3) : base;
        l.setFont(new Font("Segoe UI", Font.BOLD, fontSize(sz)));
        // Total grande em verde forte (estilo PDV); demais linhas em texto escuro.
        l.setForeground(destaqueTotal ? MARKET_GREEN : TEXT_DARK);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private JButton button(String text) {
        return styledButton(text, buttonColor(text));
    }

    /**
     * Botao chapado com cantos arredondados, hover/pressed e foco verde.
     * Pintamos manualmente o background para escapar do Nimbus, que ignora
     * setBackground em JButton.
     */
    private JButton styledButton(String text, Color base) {
        final boolean dark = base.getRed() + base.getGreen() + base.getBlue() < 520;
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color paint = base;
                ButtonModel model = getModel();
                if (!isEnabled()) {
                    paint = blend(base, Color.WHITE, 0.55f);
                } else if (model.isPressed()) {
                    paint = base.darker();
                } else if (model.isRollover()) {
                    paint = blend(base, Color.BLACK, 0.10f);
                }
                g2.setColor(paint);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setFont(new Font("Segoe UI", Font.BOLD, fontSize(13)));
        button.setForeground(dark ? Color.WHITE : new Color(0x1B, 0x1B, 0x1B));
        button.setBorder(new EmptyBorder(10, 14, 10, 14));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, compactMode() ? 38 : 44));
        button.putClientProperty("JButton.buttonType", "roundRect");
        return button;
    }

    private JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.BOLD, fontSize(16)));
        label.setForeground(MARKET_GREEN);
        label.setBorder(new EmptyBorder(0, 0, 8, 0));
        return label;
    }

    /** Titulos da coluna direita do PDV; mais compactos em telas menores. */
    private JLabel sectionTitlePdv(String text) {
        JLabel label = new JLabel(text);
        // Segoe UI Emoji garante que emojis no inicio do titulo (ex: "\uD83D\uDED2") apareçam.
        label.setFont(new Font("Segoe UI Emoji", Font.BOLD, fontSize(compactMode() ? 13 : 15)));
        label.setForeground(MARKET_GREEN);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        int b = compactMode() ? 3 : 8;
        label.setBorder(new EmptyBorder(compactMode() ? 2 : 0, 0, b, 0));
        return label;
    }

    private JPanel appHeader() {
        // Faixa verde principal (verde escuro #1b5e20 -> verde secundario #2e7d32).
        JPanel header = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint gradient = new GradientPaint(0, 0, MARKET_GREEN, getWidth(), 0, MARKET_GREEN_2);
                g2.setPaint(gradient);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(
                compactMode() ? 8 : 14,
                compactMode() ? 14 : 24,
                compactMode() ? 8 : 14,
                compactMode() ? 14 : 24));

        // Logo: carrinho dentro de pill arredondada (estilo lucide ShoppingCart).
        JLabel logo = new JLabel("\uD83D\uDED2", SwingConstants.CENTER);
        logo.setFont(new Font("Segoe UI Emoji", Font.PLAIN, fontSize(compactMode() ? 18 : 22)));
        logo.setForeground(Color.WHITE);
        int logoSize = compactMode() ? 36 : 44;
        logo.setPreferredSize(new Dimension(logoSize, logoSize));
        logo.setOpaque(false);

        JLabel brand = new JLabel("Mercado do Tonico");
        brand.setForeground(Color.WHITE);
        brand.setFont(new Font("Segoe UI", Font.BOLD, fontSize(ultraCompactMode() ? 16 : (compactMode() ? 18 : 22))));
        JLabel subtitle = new JLabel("Sistema de Gest\u00e3o PDV");
        subtitle.setForeground(new Color(0xC8, 0xE6, 0xC9));
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));

        JPanel textBlock = new JPanel();
        textBlock.setOpaque(false);
        textBlock.setLayout(new BoxLayout(textBlock, BoxLayout.Y_AXIS));
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        textBlock.add(brand);
        // Em telas pequenas, escondemos o subtitulo para liberar largura.
        if (!ultraCompactMode()) {
            textBlock.add(subtitle);
        }

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.add(logo);
        left.add(Box.createHorizontalStrut(compactMode() ? 8 : 12));
        left.add(textBlock);

        // Bloco do relogio: hora grande + data por extenso embaixo.
        java.time.format.DateTimeFormatter horaFmt =
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
        java.time.format.DateTimeFormatter dataFmt =
                java.time.format.DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy",
                        Locale.forLanguageTag("pt-BR"));

        JLabel hora = new JLabel("\u23F0  " + LocalDateTime.now().format(horaFmt));
        hora.setForeground(Color.WHITE);
        hora.setFont(new Font("Segoe UI Emoji", Font.BOLD, fontSize(compactMode() ? 13 : 15)));
        hora.setAlignmentX(Component.RIGHT_ALIGNMENT);

        JLabel dia = new JLabel(capitalizeFirst(LocalDateTime.now().format(dataFmt)));
        dia.setForeground(new Color(0xC8, 0xE6, 0xC9));
        dia.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
        dia.setAlignmentX(Component.RIGHT_ALIGNMENT);

        JPanel clockBlock = new JPanel();
        clockBlock.setOpaque(false);
        clockBlock.setLayout(new BoxLayout(clockBlock, BoxLayout.Y_AXIS));
        clockBlock.add(hora);
        // Em telas pequenas, esconder a data por extenso libera espaco util.
        if (!ultraCompactMode()) {
            clockBlock.add(dia);
        }

        // Atualiza hora a cada 1s.
        javax.swing.Timer clockTimer = new javax.swing.Timer(1000, ev -> {
            hora.setText("\u23F0  " + LocalDateTime.now().format(horaFmt));
            dia.setText(capitalizeFirst(LocalDateTime.now().format(dataFmt)));
        });
        clockTimer.setRepeats(true);
        clockTimer.start();

        // Pill do operador/usuario (fundo verde mais escuro para contraste).
        JPanel userPill = new JPanel();
        userPill.setOpaque(true);
        userPill.setBackground(new Color(0x14, 0x4D, 0x18));
        userPill.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 255, 255, 60), 1),
                new EmptyBorder(compactMode() ? 4 : 6, compactMode() ? 8 : 12,
                        compactMode() ? 4 : 6, compactMode() ? 8 : 12)));
        userPill.setLayout(new BoxLayout(userPill, BoxLayout.X_AXIS));

        JLabel userIcon = new JLabel("\uD83D\uDC64");
        userIcon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, fontSize(compactMode() ? 14 : 18)));
        userIcon.setForeground(new Color(0xC8, 0xE6, 0xC9));

        JLabel userText = new JLabel(roleLabel(user.role));
        userText.setForeground(Color.WHITE);
        userText.setFont(new Font("Segoe UI", Font.BOLD, fontSize(compactMode() ? 11 : 13)));
        JLabel userSub = new JLabel(user.nome);
        userSub.setForeground(new Color(0xC8, 0xE6, 0xC9));
        userSub.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(10)));

        JPanel userText2 = new JPanel();
        userText2.setOpaque(false);
        userText2.setLayout(new BoxLayout(userText2, BoxLayout.Y_AXIS));
        userText.setAlignmentX(Component.LEFT_ALIGNMENT);
        userSub.setAlignmentX(Component.LEFT_ALIGNMENT);
        userText2.add(userText);
        userText2.add(userSub);

        userPill.add(userIcon);
        userPill.add(Box.createHorizontalStrut(8));
        userPill.add(userText2);

        // Direita: relogio + barra vertical + pill usuario.
        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.add(clockBlock);
        right.add(Box.createHorizontalStrut(compactMode() ? 10 : 16));
        // Separador vertical sutil.
        JPanel sep = new JPanel();
        sep.setBackground(new Color(255, 255, 255, 40));
        sep.setPreferredSize(new Dimension(1, compactMode() ? 28 : 36));
        sep.setMaximumSize(new Dimension(1, compactMode() ? 28 : 36));
        right.add(sep);
        right.add(Box.createHorizontalStrut(compactMode() ? 10 : 16));
        right.add(userPill);

        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private static String roleLabel(String role) {
        if (role == null) return "Operador";
        switch (role.toUpperCase(Locale.ROOT)) {
            case "ADMIN": return "Administrador";
            case "GERENTE": return "Gerente";
            case "CAIXA": return "Operador";
            case "ESTOQUE": return "Estoque";
            default: return role;
        }
    }

    private static String capitalizeFirst(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private JPanel metricCard(String label, String value, Color color) {
        return metricCard(label, value, color, null);
    }

    /**
     * KPI card no estilo PDV verde: pill colorido com icone (canto esq. sup.),
     * trend percentual a direita (verde +/ vermelho -), valor grande preto e label cinza.
     */
    private JPanel metricCard(String label, String value, Color color, String trend) {
        JPanel card = new JPanel(new BorderLayout(0, compactMode() ? 8 : 12));
        card.setBackground(PANEL_BG);
        int padV = compactMode() ? 12 : 16;
        int padH = compactMode() ? 14 : 18;
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT),
                new EmptyBorder(padV, padH, padV, padH)));

        // Topo: icone-pill (esquerda) + trend % (direita)
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        JLabel iconPill = iconPillLabel(iconForKpi(label), color, compactMode() ? 40 : 48);
        top.add(iconPill, BorderLayout.WEST);
        if (trend != null && !trend.isBlank()) {
            JLabel trendLbl = new JLabel(trend);
            trendLbl.setFont(new Font("Segoe UI", Font.BOLD, fontSize(12)));
            trendLbl.setForeground(trend.trim().startsWith("-") ? MARKET_RED : MARKET_GREEN);
            trendLbl.setHorizontalAlignment(SwingConstants.RIGHT);
            top.add(trendLbl, BorderLayout.EAST);
        }
        card.add(top, BorderLayout.NORTH);

        // Centro: valor grande + label cinza embaixo
        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        JLabel v = new JLabel(value);
        v.setFont(new Font("Segoe UI", Font.BOLD, fontSize(compactMode() ? 22 : 28)));
        v.setForeground(TEXT_DARK);
        v.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel(label);
        l.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));
        l.setForeground(TEXT_MUTED);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(v);
        center.add(Box.createVerticalStrut(2));
        center.add(l);
        card.add(center, BorderLayout.CENTER);
        return card;
    }

    /** KPIs compactos da aba Relatorios. */
    private JPanel metricCardRelatorio(String label, String value, Color color) {
        JPanel card = new JPanel(new BorderLayout(0, compactMode() ? 4 : 6));
        card.setBackground(PANEL_BG);
        int padV = compactMode() ? 8 : 10;
        int padH = compactMode() ? 10 : 12;
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT),
                new EmptyBorder(padV, padH, padV, padH)));

        JLabel iconPill = iconPillLabel(iconForKpi(label), color, compactMode() ? 26 : 32);
        card.add(iconPill, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel v = new JLabel(value);
        v.setFont(new Font("Segoe UI", Font.BOLD, fontSize(compactMode() ? 16 : 20)));
        v.setForeground(TEXT_DARK);
        v.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel(label);
        l.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(compactMode() ? 9 : 11)));
        l.setForeground(TEXT_MUTED);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(v);
        text.add(Box.createVerticalStrut(1));
        text.add(l);
        card.add(text, BorderLayout.CENTER);
        return card;
    }

    /** Pill quadrado colorido com emoji centralizado (estilo lucide bg-color/100 + text-color/600). */
    private JLabel iconPillLabel(String icon, Color color, int size) {
        JLabel pill = new JLabel(icon == null ? "" : icon, SwingConstants.CENTER);
        pill.setOpaque(true);
        pill.setBackground(blend(color, Color.WHITE, 0.78f));
        pill.setForeground(color);
        pill.setFont(new Font("Segoe UI Emoji", Font.BOLD, fontSize(Math.max(14, size / 2))));
        pill.setPreferredSize(new Dimension(size, size));
        pill.setMaximumSize(new Dimension(size, size));
        pill.setMinimumSize(new Dimension(size, size));
        pill.setBorder(BorderFactory.createLineBorder(blend(color, Color.WHITE, 0.55f)));
        return pill;
    }

    /** Mapeia nomes comuns de KPI -> icone unicode. */
    private static String iconForKpi(String label) {
        if (label == null) return "\uD83D\uDCCA"; // bar chart
        String t = label.toLowerCase(Locale.ROOT);
        if (t.contains("vendas")) return "\uD83D\uDCB2";          // dollar sign emoji
        if (t.contains("recei")) return "\uD83D\uDCC8";          // chart up
        if (t.contains("despe")) return "\uD83D\uDCC9";          // chart down
        if (t.contains("saldo")) return "\uD83D\uDCB0";          // money bag
        if (t.contains("total de vendas") || t.contains("vendas total")) return "\uD83D\uDED2"; // cart
        if (t.contains("estoque baixo") || t.contains("baixo")) return "\u26A0\uFE0F"; // warning
        if (t.contains("vencen")) return "\u23F0";               // alarm clock
        if (t.contains("estoque") || t.contains("produtos")) return "\uD83D\uDCE6"; // package
        if (t.contains("client")) return "\uD83D\uDC65";         // people
        if (t.contains("fiado")) return "\uD83D\uDCB3";          // credit card
        if (t.contains("caixa")) return "\uD83D\uDCBB";          // computer
        return "\uD83D\uDCCA";
    }

    /** Mistura {@code color} com {@code base} dado um peso 0..1 (1 = totalmente base). */
    private static Color blend(Color color, Color base, float weight) {
        float w = Math.min(1f, Math.max(0f, weight));
        int r = Math.round(color.getRed() * (1 - w) + base.getRed() * w);
        int g = Math.round(color.getGreen() * (1 - w) + base.getGreen() * w);
        int b = Math.round(color.getBlue() * (1 - w) + base.getBlue() * w);
        return new Color(r, g, b);
    }

    private Color buttonColor(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        // Acoes destrutivas / saida -> vermelho
        if (lower.contains("cancelar") || lower.contains("fechar") || lower.contains("sangria")
                || lower.contains("excluir") || lower.contains("remover") || lower.contains("apagar")
                || lower.contains("estornar")) {
            return MARKET_RED;
        }
        // Acao secundaria neutra -> cinza
        if (lower.contains("limpar")) {
            return new Color(0x60, 0x6A, 0x73);
        }
        // Acoes de finalizacao / chamada para acao -> laranja (ex: "registrar venda", "finalizar", "suprimento", "imprimir cupom")
        if (lower.contains("finaliz") || lower.contains("registrar venda") || lower.contains("registrar  venda")
                || lower.contains("suprimento") || lower.contains("imprimir") || lower.contains("emitir")
                || lower.contains("cupom") || lower.contains("comprovante")) {
            return MARKET_ORANGE;
        }
        // Padrao: verde principal
        return MARKET_GREEN_2;
    }

    private void styleTable(JTable table) {
        table.setRowHeight(compactMode() ? 28 : 34);
        table.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(13)));
        table.setForeground(TEXT_DARK);
        table.setBackground(PANEL_BG);
        // Cabecalho cinza claro com texto escuro em negrito.
        javax.swing.table.JTableHeader header = table.getTableHeader();
        header.setFont(new Font("Segoe UI", Font.BOLD, fontSize(13)));
        header.setBackground(new Color(0xEE, 0xEE, 0xEE));
        header.setForeground(TEXT_DARK);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_SOFT));
        header.setReorderingAllowed(false);
        header.setOpaque(true);
        if (header.getDefaultRenderer() instanceof javax.swing.table.DefaultTableCellRenderer dtcr) {
            dtcr.setHorizontalAlignment(SwingConstants.LEFT);
        }

        table.setGridColor(new Color(0xEC, 0xEC, 0xEC));
        // Linhas zebradas via Nimbus + selecao verde claro com texto verde.
        table.setSelectionBackground(MARKET_GREEN_SOFT);
        table.setSelectionForeground(MARKET_GREEN);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        // Nao sobrescrevemos renderers per-column: preservamos badges/status/botoes
        // que outras telas instalam por cima. O zebra vem do UIManager (Nimbus).
    }

    private void setupLookAndFeel() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
            // Fundo geral neutro
            UIManager.put("control", MARKET_BG);
            UIManager.put("nimbusBase", MARKET_GREEN);
            UIManager.put("nimbusBlueGrey", new Color(0xCF, 0xD8, 0xDC));
            UIManager.put("nimbusFocus", MARKET_GREEN);
            UIManager.put("nimbusOrange", MARKET_ORANGE);
            UIManager.put("nimbusRed", MARKET_RED);
            UIManager.put("nimbusGreen", MARKET_GREEN_2);
            UIManager.put("nimbusSelectionBackground", MARKET_GREEN_SOFT);
            UIManager.put("nimbusSelectedText", MARKET_GREEN);
            UIManager.put("info", new Color(0xFF, 0xFD, 0xE7));

            // Tabbed pane: aba ativa branca com texto verde, fundo cinza claro,
            // texto das abas inativas em cinza escuro. Removemos as bordas duras.
            UIManager.put("TabbedPane.contentAreaColor", MARKET_BG);
            UIManager.put("TabbedPane.selected", PANEL_BG);
            UIManager.put("TabbedPane.background", new Color(0xEC, 0xEC, 0xEC));
            UIManager.put("TabbedPane.foreground", TEXT_MUTED);
            UIManager.put("TabbedPane.tabAreaBackground", PANEL_BG);
            UIManager.put("TabbedPane.borderHightlightColor", BORDER_SOFT);
            UIManager.put("TabbedPane.darkShadow", BORDER_SOFT);
            UIManager.put("TabbedPane.shadow", BORDER_SOFT);
            UIManager.put("TabbedPane.tabsOverlapBorder", Boolean.FALSE);
            UIManager.put("TabbedPane.font", new Font("Segoe UI", Font.BOLD, fontSize(13)));
            UIManager.put("TabbedPane[Enabled].textForeground", TEXT_MUTED);
            UIManager.put("TabbedPane[Selected].textForeground", MARKET_GREEN);

            // Tabela
            UIManager.put("Table.font", new Font("Segoe UI", Font.PLAIN, fontSize(13)));
            UIManager.put("Table.background", PANEL_BG);
            UIManager.put("Table.alternateRowColor", new Color(0xFA, 0xFA, 0xFA));
            UIManager.put("Table.selectionBackground", MARKET_GREEN_SOFT);
            UIManager.put("Table.selectionForeground", MARKET_GREEN);
            UIManager.put("Table.gridColor", new Color(0xEC, 0xEC, 0xEC));
            UIManager.put("TableHeader.font", new Font("Segoe UI", Font.BOLD, fontSize(13)));
            UIManager.put("TableHeader.background", new Color(0xEE, 0xEE, 0xEE));
            UIManager.put("TableHeader.foreground", TEXT_DARK);

            // Inputs
            UIManager.put("TextField.font", new Font("Segoe UI", Font.PLAIN, fontSize(13)));
            UIManager.put("TextField.background", Color.WHITE);
            UIManager.put("TextField.foreground", TEXT_DARK);
            UIManager.put("PasswordField.font", new Font("Segoe UI", Font.PLAIN, fontSize(13)));
            UIManager.put("ComboBox.font", new Font("Segoe UI", Font.PLAIN, fontSize(13)));
            UIManager.put("Spinner.font", new Font("Segoe UI", Font.PLAIN, fontSize(13)));

            // OptionPane
            UIManager.put("OptionPane.messageFont", new Font("Segoe UI", Font.PLAIN, fontSize(14)));
            UIManager.put("OptionPane.buttonFont", new Font("Segoe UI", Font.BOLD, fontSize(13)));
            UIManager.put("OptionPane.background", PANEL_BG);
            UIManager.put("Panel.background", MARKET_BG);

            // Scrollbar mais discreta
            UIManager.put("ScrollBar.width", scale(12));
            UIManager.put("ScrollBar.thumb", BORDER_STRONG);
            UIManager.put("ScrollBar.track", MARKET_BG);
        } catch (Exception ignored) {
            // Default Swing theme is acceptable if Nimbus is not available.
        }
    }

    private int scale(int value) {
        return (int) Math.max(10, Math.round(value * uiScaleFactor()));
    }

    private int fontSize(int value) {
        return Math.max(10, (int) Math.round(value * uiScaleFactor()));
    }

    private boolean compactMode() {
        return screenSize.width <= 1536 || screenSize.height <= 900;
    }

    /**
     * Notebooks pequenos (1280x720, 1366x768) ou janelas restauradas em
     * resolucoes ainda menores: aplicamos paddings e fontes mais agressivos
     * e escondemos elementos secundarios (data por extenso, subtitulos).
     */
    private boolean ultraCompactMode() {
        return screenSize.width <= 1366 || screenSize.height <= 768;
    }

    private double uiScaleFactor() {
        if (screenSize.width <= 1280 || screenSize.height <= 720) {
            return 0.78;
        }
        if (screenSize.width <= 1366 || screenSize.height <= 768) {
            return 0.84;
        }
        if (screenSize.width <= 1536 || screenSize.height <= 900) {
            return 0.90;
        }
        int h = screenSize.height;
        if (h >= 1200) {
            return 1.06;
        }
        return 1.0;
    }

    private void stylePdvField(JTextField field) {
        field.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(14)));
        field.setForeground(TEXT_DARK);
        field.setBackground(Color.WHITE);
        javax.swing.border.Border idle = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT, 1),
                new EmptyBorder(8, 10, 8, 10));
        // Borda dupla 2px verde quando focado: diferenca visual leve sem mudar layout.
        javax.swing.border.Border focused = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(MARKET_GREEN, 2),
                new EmptyBorder(7, 9, 7, 9));
        field.setBorder(idle);
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                field.setBorder(focused);
            }
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                field.setBorder(idle);
            }
        });
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, compactMode() ? 34 : 38));
    }

    private JPanel shortcutChip(String key, String label) {
        JPanel chip = new JPanel(new BorderLayout(compactMode() ? 4 : 6, 0));
        Color bgNormal = MARKET_GREEN_SOFT;
        Color bgHover = blend(MARKET_GREEN_SOFT, MARKET_GREEN, 0.18f);
        Color bgPressed = blend(MARKET_GREEN_SOFT, MARKET_GREEN, 0.30f);
        chip.setBackground(bgNormal);
        chip.putClientProperty("chipBgNormal", bgNormal);
        chip.putClientProperty("chipBgHover", bgHover);
        chip.putClientProperty("chipBgPressed", bgPressed);
        int pad = compactMode() ? 4 : 6;
        int padH = compactMode() ? 8 : 10;
        chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(blend(MARKET_GREEN, Color.WHITE, 0.55f)),
                new EmptyBorder(pad, padH, pad, padH)
        ));
        chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        chip.setToolTipText(key + " - " + label);

        // Pill com a tecla (fundo verde escuro + letra branca em negrito).
        JLabel keyLabel = new JLabel(key, SwingConstants.CENTER);
        keyLabel.setFont(new Font("Segoe UI", Font.BOLD, fontSize(compactMode() ? 10 : 11)));
        keyLabel.setOpaque(true);
        keyLabel.setBackground(MARKET_GREEN);
        keyLabel.setForeground(Color.WHITE);
        keyLabel.setBorder(new EmptyBorder(2, 6, 2, 6));

        JLabel textLabel = new JLabel(label);
        textLabel.setFont(new Font("Segoe UI", Font.BOLD, fontSize(compactMode() ? 10 : 12)));
        textLabel.setForeground(MARKET_GREEN);
        chip.add(keyLabel, BorderLayout.WEST);
        chip.add(textLabel, BorderLayout.CENTER);
        return chip;
    }

    /**
     * Wires the visual chip {@code chip} to {@code action}, making the whole
     * chip (and any inner labels) react to mouse clicks with hover/pressed
     * feedback. The chip remains a {@link JPanel}, so the existing layout is
     * preserved.
     */
    private void attachChipAction(JPanel chip, Runnable action) {
        if (chip == null || action == null) {
            return;
        }
        Color bgNormal = (Color) chip.getClientProperty("chipBgNormal");
        Color bgHover = (Color) chip.getClientProperty("chipBgHover");
        Color bgPressed = (Color) chip.getClientProperty("chipBgPressed");
        MouseAdapter listener = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (bgHover != null) {
                    chip.setBackground(bgHover);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (bgNormal != null) {
                    chip.setBackground(bgNormal);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && bgPressed != null) {
                    chip.setBackground(bgPressed);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                boolean inside = chip.contains(SwingUtilities.convertPoint(
                        e.getComponent(), e.getPoint(), chip));
                if (bgHover != null && bgNormal != null) {
                    chip.setBackground(inside ? bgHover : bgNormal);
                }
                if (inside) {
                    try {
                        action.run();
                    } catch (Exception ex) {
                        error(ex);
                    }
                }
            }
        };
        chip.addMouseListener(listener);
        for (Component c : chip.getComponents()) {
            c.addMouseListener(listener);
            if (c instanceof JComponent jc) {
                jc.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }
        }
    }

    private void refreshFrame() {
        if (mainTabs != null) {
            pendingTabIndex = mainTabs.getSelectedIndex();
        }
        frame.dispose();
        buildFrame();
    }

    private BigDecimal cardSalesToday(long caixaId) {
        try {
            Map<String, Object> row = one("""
                select coalesce(sum(vp.valor),0) as total
                from venda_pagamentos vp
                join vendas v on v.id = vp.venda_id
                where v.caixa_id = ?
                  and v.status = 'CONCLUIDA'
                  and vp.forma in ('DEBITO', 'CREDITO')
                  and date(v.timestamp) = date('now')
                """, caixaId);
            return row == null ? BigDecimal.ZERO : money(String.valueOf(row.get("total")));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Total recebido em DINHEIRO no caixa informado HOJE (somente vendas
     * com status CONCLUIDA). Usado para mostrar o saldo atualizado no
     * campo "Fundo" do PDV (abertura_valor + dinheiro recebido).
     */
    private BigDecimal cashSalesToday(long caixaId) {
        try {
            Map<String, Object> row = one("""
                select coalesce(sum(vp.valor),0) as total
                from venda_pagamentos vp
                join vendas v on v.id = vp.venda_id
                where v.caixa_id = ?
                  and v.status = 'CONCLUIDA'
                  and vp.forma = 'DINHEIRO'
                  and date(v.timestamp) = date('now')
                """, caixaId);
            return row == null ? BigDecimal.ZERO : money(String.valueOf(row.get("total")));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Senha de administrador exigida para autorizar o aumento do limite
     * de convenio (FIADO) direto do PDV. Mantida igual a senha usada em
     * {@link ClienteEditDialog} pra nao confundir o operador.
     */
    private static final String SENHA_AUMENTAR_LIMITE_CONVENIO = "admin123";

    /**
     * Valida se o cliente do convenio pode receber mais {@code valorFiado}
     * sem estourar o limite de credito. Se estourar, oferece ao operador
     * (1) ajustar o lancamento, ou (2) aumentar o limite com senha de
     * administrador.
     *
     * @return {@code true} se a venda pode prosseguir (limite ok ou
     *         elevado com sucesso), {@code false} se o operador cancelou
     *         ou nao informou senha valida.
     */
    private boolean validarLimiteFiadoOuAumentar(long clienteId, BigDecimal valorFiado) {
        try {
            Map<String, Object> cliente = one(
                    "select nome, coalesce(limite_credito, 0) as limite from clientes where id = ?",
                    clienteId);
            if (cliente == null) {
                msg("Cliente do convenio nao encontrado.");
                return false;
            }
            String nomeCliente = String.valueOf(cliente.get("nome"));
            BigDecimal limiteAtual = money(String.valueOf(cliente.get("limite")));
            Map<String, Object> abertoRow = one("""
                    select coalesce(sum(valor - valor_pago), 0) as aberto
                    from fiado where cliente_id = ? and status = 'ABERTO'
                    """, clienteId);
            BigDecimal saldoAberto = abertoRow == null
                    ? BigDecimal.ZERO
                    : money(String.valueOf(abertoRow.get("aberto")));
            BigDecimal totalAposVenda = saldoAberto.add(valorFiado);
            if (totalAposVenda.compareTo(limiteAtual) <= 0) {
                return true;
            }
            BigDecimal disponivel = limiteAtual.subtract(saldoAberto).max(BigDecimal.ZERO);
            String mensagem = "<html>"
                    + "<b>Limite de convenio insuficiente.</b><br><br>"
                    + "Cliente: <b>" + nomeCliente + "</b><br>"
                    + "Limite definido: <b>R$ " + moneyInputText(limiteAtual) + "</b><br>"
                    + "Ja em aberto: <b>R$ " + moneyInputText(saldoAberto) + "</b><br>"
                    + "Disponivel hoje: <b>R$ " + moneyInputText(disponivel) + "</b><br>"
                    + "Valor desta venda no convenio: <b>R$ " + moneyInputText(valorFiado) + "</b><br>"
                    + "<span style='color:#b91c1c'>Total ficaria em: "
                    + "<b>R$ " + moneyInputText(totalAposVenda) + "</b></span><br><br>"
                    + "Deseja <b>aumentar o limite</b> deste cliente para concluir a venda?"
                    + "</html>";
            int escolha = JOptionPane.showConfirmDialog(
                    frame,
                    mensagem,
                    "Limite do convenio excedido",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (escolha != JOptionPane.YES_OPTION) {
                return false;
            }
            return abrirDialogoAumentarLimite(clienteId, nomeCliente, limiteAtual, totalAposVenda);
        } catch (Exception ex) {
            error(ex);
            return false;
        }
    }

    /**
     * Dialogo modal que pede o novo limite e a senha de administrador
     * antes de gravar a alteracao em {@code clientes.limite_credito}.
     *
     * @param minimoNecessario menor valor aceitavel pra liberar a venda
     *                         atual (saldo aberto + parcela em FIADO).
     * @return {@code true} se o limite foi salvo com sucesso, permitindo
     *         que a venda prossiga; {@code false} caso contrario.
     */
    private boolean abrirDialogoAumentarLimite(long clienteId, String nomeCliente,
            BigDecimal limiteAtual, BigDecimal minimoNecessario) {
        final boolean[] sucesso = {false};
        JDialog dialog = new JDialog(frame, "Aumentar limite de convenio", true);
        dialog.setLayout(new BorderLayout(0, 0));
        dialog.getContentPane().setBackground(new Color(248, 248, 244));

        JLabel header = new JLabel("<html>"
                + "Cliente: <b>" + nomeCliente + "</b><br>"
                + "Limite atual: <b>R$ " + moneyInputText(limiteAtual) + "</b><br>"
                + "Minimo necessario para esta venda: "
                + "<b>R$ " + moneyInputText(minimoNecessario) + "</b>"
                + "</html>");
        header.setBorder(new EmptyBorder(14, 16, 6, 16));
        header.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(13)));

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        form.setBorder(new EmptyBorder(6, 16, 8, 16));

        JTextField novoLimite = new JTextField(moneyInputText(minimoNecessario));
        novoLimite.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(14)));
        novoLimite.setColumns(12);
        bindMoneyMaskLive(novoLimite);

        JPasswordField senha = new JPasswordField();
        senha.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(14)));
        senha.setColumns(14);

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 8);
        gc.anchor = GridBagConstraints.WEST;

        gc.gridx = 0; gc.gridy = 0;
        form.add(new JLabel("Novo limite (R$):"), gc);
        gc.gridx = 1;
        form.add(novoLimite, gc);

        gc.gridx = 0; gc.gridy = 1;
        form.add(new JLabel("Senha do administrador:"), gc);
        gc.gridx = 1;
        form.add(senha, gc);

        JLabel status = new JLabel(" ");
        status.setForeground(new Color(185, 28, 28));
        status.setBorder(new EmptyBorder(0, 16, 4, 16));
        status.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));

        JButton cancelar = button("Cancelar");
        JButton confirmar = button("\u2705  Confirmar e liberar venda");
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        footer.setOpaque(false);
        footer.add(cancelar);
        footer.add(confirmar);

        Runnable tentarConfirmar = () -> {
            BigDecimal valor;
            try {
                valor = money(novoLimite.getText());
            } catch (Exception ex) {
                status.setText("Valor invalido. Use formato 0,00.");
                novoLimite.requestFocusInWindow();
                return;
            }
            if (valor.signum() < 0) {
                status.setText("O limite nao pode ser negativo.");
                novoLimite.requestFocusInWindow();
                return;
            }
            if (valor.compareTo(minimoNecessario) < 0) {
                status.setText("O novo limite precisa ser pelo menos R$ "
                        + moneyInputText(minimoNecessario) + ".");
                novoLimite.requestFocusInWindow();
                return;
            }
            String digitada = new String(senha.getPassword());
            if (!SENHA_AUMENTAR_LIMITE_CONVENIO.equals(digitada)) {
                status.setText("Senha de administrador incorreta.");
                senha.setText("");
                senha.requestFocusInWindow();
                return;
            }
            try {
                update("update clientes set limite_credito = ? where id = ?",
                        valor, clienteId);
                audit("LIMITE_CONVENIO_ALTERADO",
                        "Cliente #" + clienteId + " " + nomeCliente
                        + " | de R$ " + moneyInputText(limiteAtual)
                        + " para R$ " + moneyInputText(valor)
                        + " (autorizado no PDV)");
                sucesso[0] = true;
                convenioRefresher.run();
                dialog.dispose();
            } catch (Exception ex) {
                status.setText("Erro ao salvar: " + ex.getMessage());
            }
        };

        confirmar.addActionListener(e -> tentarConfirmar.run());
        cancelar.addActionListener(e -> dialog.dispose());
        senha.addActionListener(e -> tentarConfirmar.run());

        JPanel root = new JPanel(new BorderLayout());
        root.setOpaque(false);
        root.add(header, BorderLayout.NORTH);
        root.add(form, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(status, BorderLayout.NORTH);
        south.add(footer, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);
        dialog.add(root, BorderLayout.CENTER);

        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        SwingUtilities.invokeLater(senha::requestFocusInWindow);
        dialog.setVisible(true);

        return sucesso[0];
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
            throw new AppException("Seu perfil nao possui acesso ao convênio.");
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

    private BigDecimal safeMoney(String sql, Object... args) {
        try {
            Map<String, Object> row = one(sql, args);
            if (row == null || row.isEmpty()) {
                return BigDecimal.ZERO;
            }
            Object value = row.values().iterator().next();
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

    private void bindMoneyMask(JTextField field) {
        // Mascara CLASSICA de dinheiro: so corrige o formato quando o
        // operador SAI do campo (focusLost). Usada nos campos do PDV
        // onde caixa esta acostumado a digitar "100" e esperar 100,00.
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                try {
                    field.setText(moneyInputText(money(field.getText())));
                } catch (Exception ignored) {
                    // Mantem o valor digitado para validacao normal do fluxo.
                }
            }
        });
    }

    /**
     * Mascara LIVE de dinheiro pt-BR: enquanto o operador digita, o sistema
     * mantem o campo sempre formatado como "1.234,56". Os 2 ultimos digitos
     * sao centavos automaticamente. Estilo calculadora - digite "2200"
     * pra ver "22,00", "100000" pra ver "1.000,00", "5" pra ver "0,05".
     * Tambem reaplica formatacao na perda de foco como rede de seguranca
     * para valores colados / vindos do banco em formato anomalo.
     */
    public static void bindMoneyMaskLive(JTextField field) {
        // Reformata o valor inicial (caso seja "500,00", "0,00", etc.)
        // em invokeLater pra nao mexer no documento durante construcao.
        SwingUtilities.invokeLater(() -> {
            String txt = field.getText();
            if (txt == null) return;
            String digitos = txt.replaceAll("\\D", "");
            if (!digitos.isEmpty()) {
                try {
                    field.setText(formatMoneyCents(Long.parseLong(digitos)));
                } catch (NumberFormatException ignored) { /* numero longo demais */ }
            }
        });
        field.getDocument().addDocumentListener(new DocumentListener() {
            private boolean updating = false;
            @Override public void insertUpdate(DocumentEvent e) { handle(); }
            @Override public void removeUpdate(DocumentEvent e) { handle(); }
            @Override public void changedUpdate(DocumentEvent e) { /* attrs */ }
            private void handle() {
                if (updating) return;
                SwingUtilities.invokeLater(() -> {
                    updating = true;
                    try {
                        String texto = field.getText();
                        String digitos = texto.replaceAll("\\D", "");
                        if (digitos.isEmpty()) {
                            // Vazio - deixa vazio (operador apagou tudo).
                            if (!texto.isEmpty()) field.setText("");
                            return;
                        }
                        // Limita a 13 digitos pra nao estourar long
                        // (max ~99.999.999.999,99 - mais que suficiente).
                        if (digitos.length() > 13) digitos = digitos.substring(0, 13);
                        long centavos;
                        try {
                            centavos = Long.parseLong(digitos);
                        } catch (NumberFormatException nfe) {
                            return;
                        }
                        String formatado = formatMoneyCents(centavos);
                        if (!formatado.equals(texto)) {
                            field.setText(formatado);
                            field.setCaretPosition(formatado.length());
                        }
                    } finally { updating = false; }
                });
            }
        });
    }

    /** Formata um total em centavos como "1.234,56" no padrao pt-BR. */
    public static String formatMoneyCents(long centavos) {
        if (centavos < 0) centavos = 0;
        long reais = centavos / 100;
        int cent = (int) (centavos % 100);
        // String.format("%,d", reais) usa virgula como separador de milhar
        // no Locale default - trocamos por ponto pra ficar pt-BR.
        String reaisStr = String.format(Locale.US, "%,d", reais).replace(',', '.');
        return reaisStr + "," + String.format("%02d", cent);
    }

    /**
     * Aplica mascara dinamica de CPF (000.000.000-00) no campo enquanto o
     * operador digita. Aceita apenas digitos (filtra letras / simbolos
     * automaticamente), insere os pontos e o tracinho na posicao correta
     * e limita a 11 digitos. Tambem coloca um tooltip de ajuda.
     */
    public static void bindCpfMask(JTextField field) {
        field.setToolTipText("Formato: 000.000.000-00 (apenas numeros - os pontos e o traco sao colocados automaticamente)");
        field.getDocument().addDocumentListener(new DocumentListener() {
            private boolean updating = false;
            @Override public void insertUpdate(DocumentEvent e) { handle(); }
            @Override public void removeUpdate(DocumentEvent e) { handle(); }
            @Override public void changedUpdate(DocumentEvent e) { /* attrs - ignora */ }
            private void handle() {
                if (updating) return;
                // SwingUtilities.invokeLater pra nao mutar o documento
                // dentro do proprio evento (lanca IllegalStateException).
                SwingUtilities.invokeLater(() -> {
                    updating = true;
                    try {
                        String texto = field.getText();
                        String digitos = texto.replaceAll("\\D", "");
                        if (digitos.length() > 11) digitos = digitos.substring(0, 11);
                        String formatado = formatCpf(digitos);
                        if (!formatado.equals(texto)) {
                            field.setText(formatado);
                            // Caret no final - operador continua digitando.
                            field.setCaretPosition(formatado.length());
                        }
                    } finally { updating = false; }
                });
            }
        });
    }

    /** Formata uma sequencia de digitos como CPF: 000.000.000-00. */
    public static String formatCpf(String digitos) {
        if (digitos == null || digitos.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digitos.length() && i < 11; i++) {
            if (i == 3 || i == 6) sb.append('.');
            if (i == 9) sb.append('-');
            sb.append(digitos.charAt(i));
        }
        return sb.toString();
    }

    /**
     * Valida CPF pelos digitos verificadores (algoritmo oficial). Aceita
     * o CPF com ou sem mascara. Rejeita CPFs com todos os digitos iguais
     * (111.111.111-11, etc) que sao considerados invalidos pela RFB.
     */
    public static boolean isValidCpf(String cpfRaw) {
        if (cpfRaw == null) return false;
        String cpf = cpfRaw.replaceAll("\\D", "");
        if (cpf.length() != 11) return false;
        if (cpf.matches("(\\d)\\1{10}")) return false;
        int[] d = new int[11];
        for (int i = 0; i < 11; i++) d[i] = cpf.charAt(i) - '0';
        int soma = 0;
        for (int i = 0; i < 9; i++) soma += d[i] * (10 - i);
        int resto = soma % 11;
        int dv1 = resto < 2 ? 0 : 11 - resto;
        if (dv1 != d[9]) return false;
        soma = 0;
        for (int i = 0; i < 10; i++) soma += d[i] * (11 - i);
        resto = soma % 11;
        int dv2 = resto < 2 ? 0 : 11 - resto;
        return dv2 == d[10];
    }

    /**
     * Cria um JTextField que pinta o placeholder (texto-guia em cinza)
     * quando esta vazio. Usado para mostrar "000.000.000-00" no campo
     * de CPF antes do operador digitar.
     */
    public static JTextField createPlaceholderField(String placeholder) {
        return new JTextField() {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty() && placeholder != null && !placeholder.isEmpty()) {
                    java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                    try {
                        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(new Color(0xBB, 0xBB, 0xBB));
                        g2.setFont(getFont());
                        java.awt.Insets ins = getInsets();
                        java.awt.FontMetrics fm = g2.getFontMetrics();
                        int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2 - 1;
                        g2.drawString(placeholder, ins.left + 4, y);
                    } finally {
                        g2.dispose();
                    }
                }
            }
        };
    }

    private String moneyInputText(BigDecimal value) {
        return BR_NUMBER.format(value == null ? BigDecimal.ZERO : value);
    }

    private BigDecimal money(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        String raw = value.trim()
                .replace("R$", "")
                .replace(" ", "")
                .replace("\u00A0", "")
                .replaceAll("[^0-9,.-]", "");
        if (raw.isBlank() || raw.equals("-")) {
            return BigDecimal.ZERO;
        }
        if (raw.contains(",")) {
            raw = raw.replace(".", "").replace(",", ".");
        }
        return new BigDecimal(raw);
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

    private record CartItem(long produtoId, String nome, BigDecimal qtd, BigDecimal preco, BigDecimal desconto) {
        CartItem(long produtoId, String nome, BigDecimal qtd, BigDecimal preco) {
            this(produtoId, nome, qtd, preco, BigDecimal.ZERO);
        }

        BigDecimal valorBrutoLinha() {
            return qtd.multiply(preco);
        }

        BigDecimal valorLiquidoLinha() {
            return valorBrutoLinha().subtract(desconto).max(BigDecimal.ZERO);
        }
    }
}
