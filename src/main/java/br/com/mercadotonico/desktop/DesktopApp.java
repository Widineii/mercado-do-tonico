package br.com.mercadotonico.desktop;

import br.com.mercadotonico.core.AppException;
import br.com.mercadotonico.core.BusinessRules;
import br.com.mercadotonico.core.PaymentAllocationService;
import br.com.mercadotonico.core.SupportLogger;
import br.com.mercadotonico.core.UserPermissions;
import br.com.mercadotonico.db.MigrationRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.w3c.dom.Element;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.image.BufferedImage;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.prefs.Preferences;

public class DesktopApp {
    private static final String APP_BRAND_NAME = "Mercearia do Tunico";
    private static final NumberFormat BRL = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"));
    private static final DecimalFormat BR_NUMBER = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("pt-BR")));
    private static final DateTimeFormatter BR_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    // Paleta inspirada na fachada da Mercearia do Tunico: verde loja + amarelo do letreiro.
    private static final Color MARKET_GREEN = new Color(0x2F, 0x7D, 0x46);        // verde principal
    private static final Color MARKET_GREEN_2 = new Color(0x5F, 0x8F, 0x55);      // verde secundario
    private static final Color MARKET_GREEN_SOFT = new Color(0xE8, 0xF5, 0xE9);   // verde claro #e8f5e9
    private static final Color MARKET_ORANGE = new Color(0xD9, 0xA6, 0x2A);       // amarelo acao do letreiro
    private static final Color MARKET_ORANGE_SOFT = new Color(0xFB, 0xF0, 0xC8);  // amarelo claro
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
    private static final Path SQLITE_DB_FILE = Path.of("data/mercado-tonico.db");
    private static final Path SQLITE_DB_FILE_LEGACY = Path.of("data/mercado-tunico.db");
    private static final String DB_URL_ENV = "MERCADO_DB_URL";
    private static final String DB_URL_CONFIG = "config/desktop.properties";
    /** Ultimo caixa selecionado no PDV (persiste ao fechar/reabrir o programa). */
    private static final Preferences DESKTOP_PREFS = Preferences.userRoot().node("br/com/mercadotonico/desktop");
    private static final String PREF_PDV_CAIXA_ID = "pdv.caixa.id";
    /** Ultima escolha no dialogo boleto/financeiro da NF-e (XML). */
    private static final String PREF_XML_NFE_BOLETO_PARCELADO = "xmlNfe.boleto.parcelado";
    private static final String PREF_XML_NFE_BOLETO_N_PARCELAS = "xmlNfe.boleto.nParcelas";
    /** Preferencia: auto | comfort | compact | ultra (sobrescrita por -Dmercadotonico.uiDensity=). */
    private static final String PREF_UI_DENSITY = "ui.density";
    /** Painel: grid "Convênio em aberto" e KPI de contagem — mesma fonte que o convênio no PDV. */
    private static final String SQL_DASHBOARD_CONVENIO_ABERTO = """
            select c.nome as Cliente, sum(f.valor - f.valor_pago) as Aberto
            from fiado f join clientes c on c.id = f.cliente_id
            where f.status = 'ABERTO'
            group by c.id
            order by Aberto desc""";
    private static final String SQL_DASHBOARD_CONVENIO_KPI_COUNT = """
            select count(distinct cliente_id) from fiado
            where status = 'ABERTO' and (valor - valor_pago) > 0""";
    /** Clientes do convênio (aba Convênio): colunas alinhadas ao cadastro + dívidas em aberto. */
    private static final String SQL_CLIENTES_CONVENIO = """
            select c.id as ID, c.nome as Nome, c.cpf as CPF,
                   coalesce(c.telefone, '') as Telefone,
                   coalesce(c.endereco, '') as Endereco,
                   c.limite_credito as Limite,
                   coalesce(sum(f.valor - f.valor_pago), 0) as Divida
            from clientes c
            left join fiado f on f.cliente_id = c.id and f.status = 'ABERTO'
            where c.bloqueado = 0
            group by c.id
            order by c.nome
            """;
    /** Produtos ativos com estoque estritamente menor que este numero aparecem no Painel. */
    private static final int DASHBOARD_ESTOQUE_CRITICO_LIMITE = 7;
    private static final String SQL_DASHBOARD_ESTOQUE_CRITICO =
            "select p.nome as Produto, p.estoque_atual as Quantidade, p.estoque_minimo as Minimo "
                    + "from produtos p where p.ativo = 1 and p.estoque_atual < " + DASHBOARD_ESTOQUE_CRITICO_LIMITE
                    + " order by p.estoque_atual, p.nome";
    private static final String SQL_DASHBOARD_ESTOQUE_CRITICO_COUNT =
            "select count(*) from produtos where ativo = 1 and estoque_atual < " + DASHBOARD_ESTOQUE_CRITICO_LIMITE;
    /**
     * Validade nos próximos 30 dias: uma linha por lote/entrada (mesmo produto pode repetir com datas diferentes)
     * + linha do cadastro do produto quando não há entrada com a mesma data.
     * Inclui barras, localização e dias até vencer para conferência no estoque.
     */
    private static final String SQL_DASHBOARD_VALIDADES_30D = """
            select x.Produto as Produto, x.Barras as "Código de barras", x.Localizacao as "Localização",
                   x.Unidade as Unidade, x.Validade as "Data de validade", x.Dias as "Dias até vencer",
                   x.Quantidade as Quantidade, x.Lote as Lote
            from (
              select p.nome as Produto,
                     coalesce(nullif(trim(p.codigo_barras), ''), '-') as Barras,
                     coalesce(nullif(trim(p.localizacao), ''), '-') as Localizacao,
                     p.unidade as Unidade, e.validade as Validade,
                     cast(julianday(date(e.validade)) - julianday(date('now', 'localtime')) as integer) as Dias,
                     e.quantidade as Quantidade,
                     coalesce(nullif(trim(e.lote), ''), '-') as Lote
                from entradas_estoque e
                join produtos p on p.id = e.produto_id and p.ativo = 1
               where e.validade is not null and trim(e.validade) <> ''
                 and date(e.validade) <= date('now', 'localtime', '+30 day')
              union all
              select p.nome as Produto,
                     coalesce(nullif(trim(p.codigo_barras), ''), '-') as Barras,
                     coalesce(nullif(trim(p.localizacao), ''), '-') as Localizacao,
                     p.unidade as Unidade, p.validade as Validade,
                     cast(julianday(date(p.validade)) - julianday(date('now', 'localtime')) as integer) as Dias,
                     p.estoque_atual as Quantidade, '-' as Lote
                from produtos p
               where p.ativo = 1 and p.validade is not null and trim(p.validade) <> ''
                 and date(p.validade) <= date('now', 'localtime', '+30 day')
                 and not exists (
                       select 1 from entradas_estoque e2
                        where e2.produto_id = p.id
                          and e2.validade is not null and trim(e2.validade) <> ''
                          and date(e2.validade) = date(p.validade))
            ) x
            order by date(x.Validade), x.Produto, x.Lote""";
    private static final String SQL_DASHBOARD_VALIDADES_30D_COUNT = """
            select count(*) from (
              select 1
                from entradas_estoque e
                join produtos p on p.id = e.produto_id and p.ativo = 1
               where e.validade is not null and trim(e.validade) <> ''
                 and date(e.validade) <= date('now', 'localtime', '+30 day')
              union all
              select 1
                from produtos p
               where p.ativo = 1 and p.validade is not null and trim(p.validade) <> ''
                 and date(p.validade) <= date('now', 'localtime', '+30 day')
                 and not exists (
                       select 1 from entradas_estoque e2
                        where e2.produto_id = p.id
                          and e2.validade is not null and trim(e2.validade) <> ''
                          and date(e2.validade) = date(p.validade))
            ) t""";
    private static final Font TITLE_FONT = new Font("Segoe UI", Font.BOLD, 30);
    private static final Font SECTION_FONT = new Font("Segoe UI", Font.BOLD, 18);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    /**
     * Dimensao do monitor usada para {@link #compactMode()}, {@link #uiScaleFactor()} e proporcoes do PDV.
     * Atualizada antes de cada {@link #buildFrame()} a partir do monitor onde a janela estava (ou tela padrao).
     */
    private Dimension uiReferenceSize = initialUiReferenceSize();
    private Connection con;
    private User user;
    private JFrame frame;
    private JTabbedPane mainTabs;
    private DefaultTableModel cartModel;
    /** Tabela do carrinho no PDV (miniaturas {@code imagem_url}). */
    private JTable pdvCartTable;
    /** Tabela "Produtos" na aba Estoque (colunas completas). */
    private JTable estoqueProdutosTable;
    private JTextField estoqueProdutosSearchField;
    private JLabel estoqueProdutosSearchStatusLabel;
    private static final String ESTOQUE_PROD_COL_IMAGEM_URL = "_imagem_url";
    private static final HttpClient PDV_CART_IMAGE_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ObjectMapper FORNECEDOR_JSON = new ObjectMapper();
    private static final HttpClient FORNECEDOR_LOOKUP_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, ImageIcon> pdvCartImageCache = new ConcurrentHashMap<>();
    private final Set<String> pdvCartImageLoading = ConcurrentHashMap.newKeySet();
    private static volatile ImageIcon pdvCartPlaceholderIcon;
    /** Produtos para os quais a senha de ADMIN ja autorizou venda acima do estoque nesta operacao. */
    private final Set<Long> pdvEstoqueAutorizadoPorAdminIds = new HashSet<>();
    private JLabel totalLabel;
    private JComboBox<Item> clienteCombo;
    private JComboBox<Item> caixaCombo;
    private DesktopInventoryService inventoryService;
    private DesktopCashReportService cashReportService;
    private DesktopProductReportService productReportService;
    private DesktopReturnService returnService;
    private DesktopFinanceService financeService;
    private DesktopReceiptService receiptService;
    private br.com.mercadotonico.integration.barcode.BarcodeLookupService barcodeLookupService;
    private Runnable paymentFeedbackUpdater = () -> {};
    /** Zerado em {@link #posPanel()}; pos-venda limpa pagamentos e atualiza totais sem recriar a janela. */
    private Runnable pdvAfterSaleCleanup = () -> {};
    /**
     * Recarrega tabelas de convênio (aba Convênio + bloco no Painel quando houver).
     * Encadeado em {@link #dashboardPanel()} e {@link #fiadoPanel()}; o PDV chama após venda FIADO / baixa.
     * O Painel também atualiza estoque critico via {@link #refreshDashboardPainelWidgets()}.
     */
    private Runnable convenioRefresher = () -> {};
    /** Tabela do card "Convênio em aberto" no Painel (null se sem permissão de relatórios). */
    private JTable dashboardConvenioAbertoTable;
    /** Tabela "pouco estoque" no Painel (null se sem relatorios). */
    private JTable dashboardEstoqueCriticoTable;
    /** KPI "Estoque baixo" no Painel. */
    private JLabel dashboardEstoqueBaixoKpiCountLabel;
    /** KPI "Vencendo em 30 dias" no Painel (linhas de lote + cadastro). */
    private JLabel dashboardValidades30KpiCountLabel;
    /** Tabela "Produtos vencendo em 30 dias" no Painel. */
    private JTable dashboardValidades30Table;
    /** Valor do KPI "Clientes com convênio em aberto" no Painel. */
    private JLabel dashboardConvenioKpiCountLabel;
    /**
     * Atualiza os campos "Fundo", "Cartao" e "Pix" no bloco do topo do PDV. Configurado em
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
    /** Campo Codigo/Descricao do PDV; F1 foca aqui (atalho global na aba PDV). */
    private JTextField pdvCodigoField;
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
        productReportService = new DesktopProductReportService(con);
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
                        refreshEstoqueViews();
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
                """, "Administrador Tunico", "admin", encoder.encode("admin123"), "ADMIN", encoder.encode("1234"), 30, 1);
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
        JTextField login = new JTextField();
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
        if (!showBrandedLoginDialog(login, senha)) {
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

    private boolean showBrandedLoginDialog(JTextField login, JPasswordField senha) {
        final boolean[] accepted = {false};
        JDialog dialog = new JDialog((Frame) null, APP_BRAND_NAME + " - Entrar", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setResizable(false);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(PANEL_BG);
        root.setBorder(BorderFactory.createLineBorder(MARKET_GREEN, 1));

        JPanel header = new JPanel(new BorderLayout(12, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(0, 0, MARKET_GREEN, getWidth(), 0, MARKET_GREEN_2));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(14, 18, 14, 18));

        JPanel titleBox = new JPanel();
        titleBox.setOpaque(false);
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(APP_BRAND_NAME);
        title.setForeground(Color.WHITE);
        title.setFont(new Font("Segoe UI", Font.BOLD, 24));
        JLabel subtitle = new JLabel("Sistema de gestao e PDV");
        subtitle.setForeground(new Color(0xF6, 0xE8, 0xB3));
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        titleBox.add(title);
        titleBox.add(subtitle);
        header.add(createLoginBrandMark(), BorderLayout.WEST);
        header.add(titleBox, BorderLayout.CENTER);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        form.setBorder(new EmptyBorder(20, 24, 14, 24));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridx = 0;
        gc.gridy = 0;
        gc.weightx = 0;
        JLabel loginLabel = label("Login");
        loginLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        form.add(loginLabel, gc);
        gc.gridx = 1;
        gc.weightx = 1;
        login.setPreferredSize(new Dimension(230, 34));
        stylePdvField(login);
        form.add(login, gc);

        gc.gridx = 0;
        gc.gridy = 1;
        gc.weightx = 0;
        JLabel senhaLabel = label("Senha");
        senhaLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        form.add(senhaLabel, gc);
        gc.gridx = 1;
        gc.weightx = 1;
        senha.setPreferredSize(new Dimension(230, 34));
        stylePdvField(senha);
        form.add(senha, gc);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        actions.setBorder(new EmptyBorder(0, 24, 20, 24));
        JButton cancel = new JButton("Cancelar");
        JButton ok = new JButton("Entrar");
        ok.setFont(new Font("Segoe UI", Font.BOLD, 13));
        ok.setForeground(Color.WHITE);
        ok.setBackground(MARKET_GREEN);
        ok.setFocusPainted(false);
        ok.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(MARKET_GREEN.darker()),
                new EmptyBorder(7, 18, 7, 18)));
        cancel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        cancel.setFocusPainted(false);
        cancel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(190, 196, 200)),
                new EmptyBorder(7, 16, 7, 16)));
        ok.addActionListener(e -> {
            accepted[0] = true;
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());
        actions.add(cancel);
        actions.add(ok);

        root.add(header, BorderLayout.NORTH);
        root.add(form, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        SwingUtilities.invokeLater(() -> {
            login.requestFocusInWindow();
            login.selectAll();
        });
        dialog.setVisible(true);
        return accepted[0];
    }

    private JComponent createLoginBrandMark() {
        return new JComponent() {
            {
                setPreferredSize(new Dimension(62, 62));
                setMinimumSize(new Dimension(62, 62));
                setMaximumSize(new Dimension(62, 62));
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int s = Math.min(getWidth(), getHeight()) - 4;
                int x = (getWidth() - s) / 2;
                int y = (getHeight() - s) / 2;
                g2.setColor(new Color(0xC7, 0x73, 0x24));
                g2.fillRoundRect(x, y, s, s, 14, 14);
                g2.setColor(new Color(0xF4, 0xD5, 0x72));
                g2.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(x + 15, y + 20, x + 29, y + 20);
                g2.drawLine(x + 29, y + 20, x + 40, y + 34);
                g2.drawLine(x + 18, y + 36, x + 43, y + 36);
                g2.fillOval(x + 20, y + 42, 7, 7);
                g2.fillOval(x + 39, y + 42, 7, 7);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Segoe UI", Font.BOLD, 22));
                g2.drawString("T", x + 25, y + 31);
                g2.dispose();
            }
        };
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

    private static Dimension initialUiReferenceSize() {
        try {
            Rectangle b = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
            return new Dimension(b.width, b.height);
        } catch (Exception e) {
            Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
            return new Dimension(d.width, d.height);
        }
    }

    private void buildFrame() {
        boolean firstBuild = frame == null;
        if (firstBuild) {
            frame = new JFrame(APP_BRAND_NAME + " - Sistema Desktop");
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        } else {
            frame.setTitle(APP_BRAND_NAME + " - Sistema Desktop");
        }
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
        if (firstBuild) {
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        }
        pdvFormaPagamentoRoot = null;
        pdvFinalizarButton = null;
        pdvLimparCarrinhoButton = null;
        pdvCodigoField = null;
        pdvCartTable = null;
        estoqueProdutosTable = null;
        estoqueProdutosSearchField = null;
        estoqueProdutosSearchStatusLabel = null;
        convenioRefresher = () -> {};
        dashboardConvenioAbertoTable = null;
        dashboardConvenioKpiCountLabel = null;
        dashboardEstoqueCriticoTable = null;
        dashboardEstoqueBaixoKpiCountLabel = null;
        dashboardValidades30Table = null;
        dashboardValidades30KpiCountLabel = null;
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
            addNavTab("Relatórios", "\uD83D\uDCCA", tabWithAutoScroll(relatoriosPanel()));
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
        mainTabs.addChangeListener(e -> {
            refreshHeaders.run();
            updatePdvDefaultButton();
            if (pdvTabIndex >= 0 && mainTabs != null && mainTabs.getSelectedIndex() == pdvTabIndex) {
                pdvHeaderRefresher.run();
            }
            if (mainTabs != null && mainTabs.getSelectedIndex() == 0) {
                refreshDashboardPainelWidgets();
            }
        });
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
        frame.revalidate();
        frame.repaint();
        if (firstBuild) {
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        }
        if (pdvTabIndex >= 0) {
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
     * Atalhos do PDV que nem sempre chegam ao InputMap do painel (combo,
     * tabela, scroll): F1 foca codigo; F2 suprimento; F3 sangria; F6 limpa carrinho;
     * F4 no combo forma de pagamento finaliza venda (Windows abria a lista do combo com F4).
     */
    private void installPdvF4ShortcutDispatcher(JFrame frame) {
        if (pdvF4Dispatcher != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(pdvF4Dispatcher);
            pdvF4Dispatcher = null;
        }
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
        if (code == KeyEvent.VK_F1 && pdvCodigoField != null) {
            SwingUtilities.invokeLater(() -> {
                pdvCodigoField.requestFocusInWindow();
                pdvCodigoField.selectAll();
            });
            ev.consume();
            return true;
        }
        if (code == KeyEvent.VK_F2) {
            SwingUtilities.invokeLater(() -> caixaOperacao("SUPRIMENTO"));
            ev.consume();
            return true;
        }
        if (code == KeyEvent.VK_F3) {
            SwingUtilities.invokeLater(() -> caixaOperacao("SANGRIA"));
            ev.consume();
            return true;
        }
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
        // Campos e paineis nao repassam a roda ao JScrollPane da aba; sem isso a barra aparece mas o mouse nao rola.
        installPdvMouseWheelScrollForwarding(content);
        return scroll;
    }

    private JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu menu = new JMenu(APP_BRAND_NAME);
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

        JMenu tela = new JMenu("Tela");
        JMenuItem ajustarMonitor = new JMenuItem("Ajustar interface ao monitor atual");
        ajustarMonitor.setToolTipText("Recarrega layout e fontes usando a resolucao do monitor onde a janela esta. "
                + "Use ao mudar de PC/monitor ou apos alterar a escala do Windows.");
        ajustarMonitor.addActionListener(e -> refreshFrame());
        tela.add(ajustarMonitor);
        tela.addSeparator();
        JMenu densidade = new JMenu("Densidade da interface");
        Consumer<String> setDensidade = modo -> {
            DESKTOP_PREFS.put(PREF_UI_DENSITY, modo);
            refreshEstoqueViews();
        };
        JMenuItem dAuto = new JMenuItem("Automatica (recomendado)");
        dAuto.setToolTipText("Compacta em telas pequenas (ex.: 1366x768) e folga em telas grandes.");
        dAuto.addActionListener(e -> setDensidade.accept("auto"));
        JMenuItem dComfort = new JMenuItem("Conforto (telas grandes)");
        dComfort.setToolTipText("Mais espaco e fonte levemente maior — util em monitores grandes (ex.: 30\").");
        dComfort.addActionListener(e -> setDensidade.accept("comfort"));
        JMenuItem dCompact = new JMenuItem("Compacta");
        dCompact.setToolTipText("Mais itens na tela; util em 17\" ou notebooks com muita informacao.");
        dCompact.addActionListener(e -> setDensidade.accept("compact"));
        JMenuItem dUltra = new JMenuItem("Muito compacta");
        dUltra.setToolTipText("Minima ocupacao de espaco (resolucoes muito baixas).");
        dUltra.addActionListener(e -> setDensidade.accept("ultra"));
        densidade.add(dAuto);
        densidade.add(dComfort);
        densidade.add(dCompact);
        densidade.add(dUltra);
        tela.add(densidade);
        bar.add(tela);
        return bar;
    }

    private JPanel dashboardPanel() {
        JPanel panel = page();
        panel.add(title("Painel de operação"));
        if (!UserPermissions.canAccessReports(user.role)) {
            panel.add(roleDashboard());
            return panel;
        }
        JPanel metrics = new JPanel(new GridLayout(1, 4, 12, 12));
        metrics.setOpaque(false);
        metrics.add(metricCard("Produtos cadastrados", String.valueOf(safeInt("select count(*) from produtos where ativo=1")), MARKET_GREEN_2));
        JLabel[] estoqueKpiRef = new JLabel[1];
        metrics.add(metricCard("Estoque baixo",
                String.valueOf(safeInt(SQL_DASHBOARD_ESTOQUE_CRITICO_COUNT)),
                MARKET_RED,
                null,
                estoqueKpiRef));
        dashboardEstoqueBaixoKpiCountLabel = estoqueKpiRef[0];
        JLabel[] validadeKpiRef = new JLabel[1];
        metrics.add(metricCard("Vencendo em 30 dias",
                String.valueOf(safeInt(SQL_DASHBOARD_VALIDADES_30D_COUNT)),
                MARKET_ORANGE,
                null,
                validadeKpiRef));
        dashboardValidades30KpiCountLabel = validadeKpiRef[0];
        JLabel[] convenioKpiValueRef = new JLabel[1];
        metrics.add(metricCard("Clientes com convênio em aberto",
                String.valueOf(safeInt(SQL_DASHBOARD_CONVENIO_KPI_COUNT)),
                MARKET_GREEN,
                null,
                convenioKpiValueRef));
        dashboardConvenioKpiCountLabel = convenioKpiValueRef[0];
        panel.add(metrics);
        panel.add(Box.createVerticalStrut(14));
        JPanel grid = new JPanel(new GridLayout(2, 2, 14, 14));
        grid.setOpaque(false);
        grid.add(section("Status dos caixas", table("select c.numero as Caixa, c.status as Status, coalesce(u.nome, '-') as Operador from caixas c left join usuarios u on u.id=c.operador_atual_id order by c.numero")));
        dashboardEstoqueCriticoTable = table(SQL_DASHBOARD_ESTOQUE_CRITICO);
        grid.add(section("Produto com baixo estoque", dashboardEstoqueCriticoTable));
        dashboardValidades30Table = table(SQL_DASHBOARD_VALIDADES_30D);
        grid.add(section("Produtos vencendo em 30 dias", dashboardValidades30Table));
        dashboardConvenioAbertoTable = table(SQL_DASHBOARD_CONVENIO_ABERTO);
        grid.add(section("Convênio em aberto", dashboardConvenioAbertoTable));
        panel.add(grid);
        Runnable antesPainel = convenioRefresher;
        convenioRefresher = () -> {
            antesPainel.run();
            SwingUtilities.invokeLater(this::refreshDashboardPainelWidgets);
        };
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
            wrapper.add(section("Reposicao prioritaria", table(
                    "select nome as Produto, estoque_atual as Atual, estoque_minimo as Minimo, validade as Validade "
                            + "from produtos where ativo=1 and (estoque_atual < " + DASHBOARD_ESTOQUE_CRITICO_LIMITE
                            + " or estoque_atual <= estoque_minimo or (validade is not null and date(validade) <= date('now','+30 day'))) "
                            + "order by estoque_atual, validade limit 20")));
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
        restorePdvCaixaSelection();
        JTextField fundo = new JTextField("100,00");
        stylePdvField(fundo);
        bindMoneyMask(fundo);
        fundo.setColumns(7);
        JTextField cartaoTopo = new JTextField("0,00");
        stylePdvField(cartaoTopo);
        cartaoTopo.setColumns(7);
        cartaoTopo.setEditable(false);
        cartaoTopo.setEnabled(false);
        JTextField pixTopo = new JTextField("0,00");
        stylePdvField(pixTopo);
        pixTopo.setColumns(7);
        pixTopo.setEditable(false);
        pixTopo.setEnabled(false);
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
        JPanel chipFinalizar = shortcutChip("F4", "Registrar venda");
        JPanel chipLimpar = shortcutChip("F6", "Limpar carrinho");
        JPanel chipCancelar = shortcutChip("F7", "Cancelar item");
        JPanel chipCupom = shortcutChip("F10", "Cupom fiscal");
        JPanel chipDesconto = shortcutChip("F11", "Desconto item");
        JPanel chipEsc = shortcutChip("ESC", "Limpar campo");
        JPanel chipSuprimento = shortcutChip("F2", "Suprimento");
        chipSuprimento.setToolTipText("Entrada de dinheiro no caixa (soma ao fundo). Tecla F2.");
        JPanel chipSangria = shortcutChip("F3", "Sangria");
        chipSangria.setToolTipText("Retirada de dinheiro do caixa (do fundo). Nao pode ser maior que o dinheiro disponivel. Tecla F3.");
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
        topRow.add(chipSuprimento);
        topRow.add(chipSangria);
        topRow.add(labelLight("Caixa"));
        topRow.add(caixaCombo);
        // Fundo + Cartao na mesma linha; Pix centralizado logo abaixo (os 3 juntos).
        JPanel saldosCaixaTopo = new JPanel();
        saldosCaixaTopo.setOpaque(false);
        saldosCaixaTopo.setLayout(new BoxLayout(saldosCaixaTopo, BoxLayout.Y_AXIS));
        JPanel linhaFundoCartao = new JPanel(new FlowLayout(FlowLayout.CENTER,
                compactMode() ? 6 : 10, 0));
        linhaFundoCartao.setOpaque(false);
        linhaFundoCartao.add(labelLight("Fundo"));
        linhaFundoCartao.add(fundo);
        linhaFundoCartao.add(Box.createHorizontalStrut(compactMode() ? 8 : 14));
        linhaFundoCartao.add(labelLight("Cartao"));
        linhaFundoCartao.add(cartaoTopo);
        JPanel linhaPix = new JPanel(new FlowLayout(FlowLayout.CENTER,
                compactMode() ? 4 : 8, 0));
        linhaPix.setOpaque(false);
        linhaPix.add(labelLight("Pix"));
        linhaPix.add(pixTopo);
        saldosCaixaTopo.add(linhaFundoCartao);
        saldosCaixaTopo.add(Box.createVerticalStrut(compactMode() ? 2 : 3));
        saldosCaixaTopo.add(linhaPix);
        topRow.add(saldosCaixaTopo);
        topRow.add(abrir);
        topRow.add(voltarMenu);
        JPanel topStack = new JPanel();
        topStack.setOpaque(false);
        topStack.setLayout(new BoxLayout(topStack, BoxLayout.Y_AXIS));
        topStack.add(topRow);
        top.add(topStack, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);

        cartModel = new DefaultTableModel(new Object[]{"", "Produto", "Qtd", "V. Unit", "Subtotal", "Est."}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                // Nao usar Icon.class: com renderizadores/Nimbus pode vazar toString() do icone
                // em colunas de texto (ex.: "Produto").
                return Object.class;
            }
        };
        JTable cartTable = new JTable(cartModel);
        styleTable(cartTable);
        int cartThumbRow = compactMode() ? 48 : 56;
        cartTable.setRowHeight(cartThumbRow);
        cartTable.setBackground(Color.WHITE);
        cartTable.setRowSelectionAllowed(true);
        cartTable.setColumnSelectionAllowed(false);
        cartTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        installPdvThumbColumnRenderer(cartTable, 0);
        cartTable.getColumnModel().getColumn(0).setMinWidth(cartThumbRow);
        cartTable.getColumnModel().getColumn(0).setMaxWidth(cartThumbRow + 8);
        cartTable.getColumnModel().getColumn(0).setPreferredWidth(cartThumbRow + 4);
        pdvCartTable = cartTable;
        // Envolvemos a tabela do carrinho num card "Itens da Venda" com header verde + icone.
        JPanel cartCard = section("Itens da Venda", cartTable);
        int sideWidth;
        if (ultraCompactMode()) {
            // Em notebook pequeno (1280x720), garantimos pelo menos 280px
            // para os textos "Adicionar produto" / "Finalizar Venda" caberem.
            sideWidth = Math.max(scale(280), Math.min(scale(330), (int) (uiReferenceSize.width * 0.27)));
        } else if (compactMode()) {
            sideWidth = Math.max(scale(290), Math.min(scale(360), (int) (uiReferenceSize.width * 0.25)));
        } else {
            sideWidth = Math.max(scale(320), Math.min(scale(440), (int) (uiReferenceSize.width * 0.28)));
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
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        int sidePad = compactMode() ? 6 : 10;
        right.setBackground(MARKET_BG);
        right.setBorder(new EmptyBorder(sidePad, sidePad, sidePad, sidePad));
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
        totalLabel.setVerticalAlignment(SwingConstants.CENTER);
        totalLabel.setHorizontalTextPosition(SwingConstants.CENTER);
        totalLabel.setVerticalTextPosition(SwingConstants.CENTER);
        totalLabel.setOpaque(true);
        totalLabel.setBackground(MARKET_GREEN_SOFT);
        totalLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(blend(MARKET_GREEN, Color.WHITE, 0.65f), 1),
                new EmptyBorder(0, compactMode() ? 8 : 12, 0, compactMode() ? 8 : 12)));
        totalLabel.setFont(new Font("Segoe UI", Font.BOLD, fontSize(compactMode() ? 32 : 44)));
        totalLabel.setForeground(MARKET_GREEN);
        Dimension totalBoxSize = new Dimension(scale(compactMode() ? 170 : 180), scale(compactMode() ? 82 : 102));
        totalLabel.setPreferredSize(totalBoxSize);
        totalLabel.setMinimumSize(totalBoxSize);
        totalLabel.setMaximumSize(totalBoxSize);
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
        formaPagamento.setAlignmentX(Component.LEFT_ALIGNMENT);
        formaPagamento.setMaximumSize(new Dimension(Integer.MAX_VALUE, formaPagamento.getPreferredSize().height));

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
        valorRecebido.setToolTipText("Quanto o cliente entregou em dinheiro. O troco atualiza enquanto digita. Ao focar, seleciona tudo para digitar por cima. Apos Enter/Confirmar, o valor permanece ate finalizar a venda ou voce editar.");
        valorRecebido.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                // PDV: primeiro toque ja substitui o valor sugerido (ex.: total da compra).
                SwingUtilities.invokeLater(valorRecebido::selectAll);
            }
        });
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
        resumoTroco.setForeground(MARKET_ORANGE);
        Map<String, BigDecimal> pagamentosDigitados = new LinkedHashMap<>();
        final BigDecimal[] dinheiroRecebidoInformado = {BigDecimal.ZERO};
        // Holder do "auto-pick" do cliente do convenio. E preenchido mais
        // abaixo (no setup do autocomplete) e chamado tanto pelo Finalizar
        // Venda quanto pelo Adicionar pagamento. Ele pega a sugestao
        // destacada no popup quando o operador apertou Finalizar sem ter
        // dado Enter na sugestao - cenario comum no caixa.
        final Runnable[] autoPickClienteConvenio = new Runnable[]{ () -> {} };
        // Botoes de acao do PDV: altura moderada (estilo caixa / referencia visual).
        JButton finalizar = styledButton("\uD83D\uDCB2  Finalizar Venda", MARKET_ORANGE);
        finalizar.setFont(new Font("Segoe UI Emoji", Font.BOLD, fontSize(compactMode() ? 13 : 15)));
        finalizar.setPreferredSize(new Dimension(0, compactMode() ? 38 : 44));
        finalizar.setMaximumSize(new Dimension(Integer.MAX_VALUE, compactMode() ? 38 : 44));
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
        limpar.setAlignmentX(Component.LEFT_ALIGNMENT);
        limpar.setPreferredSize(new Dimension(0, compactMode() ? 36 : 42));
        limpar.setMaximumSize(new Dimension(Integer.MAX_VALUE, compactMode() ? 36 : 42));
        limpar.addActionListener(e -> {
            cart.clear();
            pdvEstoqueAutorizadoPorAdminIds.clear();
            refreshCart();
        });
        JButton cancelarItem = button("Cancelar item");
        cancelarItem.addActionListener(e -> cancelarItemSelecionado(cartTable));
        JButton comprovante = button("Reemitir cupom fiscal");
        comprovante.addActionListener(e -> reemitirComprovante());
        if (modoOperador) {
            add.setFont(new Font("Segoe UI", Font.BOLD, fontSize(compactMode() ? 12 : 13)));
            finalizar.setFont(new Font("Segoe UI Emoji", Font.BOLD, fontSize(compactMode() ? 13 : 15)));
            add.setPreferredSize(new Dimension(0, compactMode() ? 32 : 38));
            finalizar.setPreferredSize(new Dimension(0, compactMode() ? 38 : 44));
            finalizar.setMaximumSize(new Dimension(Integer.MAX_VALUE, compactMode() ? 38 : 44));
        }
        finalizar.setToolTipText("Registrar venda (F4). Grava a venda, baixa estoque e aparece no relatório.");

        // Botao Cancelar Venda (vermelho) - sempre disponivel ao lado do Finalizar.
        JButton cancelarVendaBtn = styledButton("\u274C  Cancelar Venda", MARKET_RED);
        cancelarVendaBtn.setFont(new Font("Segoe UI Emoji", Font.BOLD, fontSize(compactMode() ? 12 : 13)));
        cancelarVendaBtn.setPreferredSize(new Dimension(0, compactMode() ? 36 : 42));
        cancelarVendaBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, compactMode() ? 36 : 42));
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
            pdvEstoqueAutorizadoPorAdminIds.clear();
            pagamentosDigitados.clear();
            dinheiroRecebidoInformado[0] = BigDecimal.ZERO;
            refreshCart();
            paymentFeedbackUpdater.run();
        });

        JPanel cardAdicionar = pdvCaixaSideCard();
        cardAdicionar.add(sectionTitlePdv("\uD83D\uDED2  Adicionar Item"));
        JLabel lCodDesc = label("Código / Descrição");
        lCodDesc.setAlignmentX(Component.LEFT_ALIGNMENT);
        cardAdicionar.add(lCodDesc);
        codigo.setAlignmentX(Component.LEFT_ALIGNMENT);
        cardAdicionar.add(codigo);
        // Lista de sugestoes (autocomplete) foi removida do layout do PDV
        // a pedido do operador - estava ficando muito grande e atrapalhando
        // a tela. O componente continua existindo no codigo (pra nao
        // quebrar listeners ja registrados), mas e mantido invisivel.
        sugestoesScroll.setVisible(false);
        cardAdicionar.add(Box.createVerticalStrut(4));
        JLabel lQtdTit = label("Quantidade");
        lQtdTit.setAlignmentX(Component.LEFT_ALIGNMENT);
        cardAdicionar.add(lQtdTit);
        qtd.setAlignmentX(Component.LEFT_ALIGNMENT);
        cardAdicionar.add(qtd);
        cardAdicionar.add(Box.createVerticalStrut(4));
        add.setAlignmentX(Component.LEFT_ALIGNMENT);
        add.setMaximumSize(new Dimension(Integer.MAX_VALUE, add.getPreferredSize().height));
        cardAdicionar.add(add);
        // Botao "Pesquisar produto": abre uma janela com TODOS os produtos
        // ativos cadastrados, com busca em tempo real por nome / codigo de
        // barras / SKU. Util quando o cliente nao trouxe etiqueta ou
        // quando o operador nao lembra o codigo. Para venda rapida com
        // leitor de codigo de barras, basta escanear no campo "Codigo /
        // Descricao" - se o produto ja estiver no carrinho, a quantidade
        // e somada automaticamente (mesmo produtoId) sem abrir janela.
        cardAdicionar.add(Box.createVerticalStrut(compactMode() ? 4 : 6));
        JButton pesquisarProduto = button("\uD83D\uDD0D  Pesquisar produto");
        pesquisarProduto.setToolTipText("Abre uma janela com todos os produtos. Escolha um item: ele preenche Código / Descrição; ajuste Quantidade e clique Adicionar produto.");
        pesquisarProduto.addActionListener(e -> abrirPesquisaProduto(codigo));
        pesquisarProduto.setAlignmentX(Component.LEFT_ALIGNMENT);
        pesquisarProduto.setMaximumSize(new Dimension(Integer.MAX_VALUE, pesquisarProduto.getPreferredSize().height));
        cardAdicionar.add(pesquisarProduto);

        JPanel cardPagamento = pdvCaixaSideCard();
        JPanel totalWrap = new JPanel();
        totalWrap.setLayout(new BoxLayout(totalWrap, BoxLayout.Y_AXIS));
        totalWrap.setOpaque(false);
        totalWrap.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel totalTitle = sectionTitlePdv("\uD83D\uDCB0  TOTAL DA COMPRA");
        totalTitle.setHorizontalAlignment(SwingConstants.CENTER);
        totalTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        totalLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        totalWrap.add(totalTitle);
        totalWrap.add(totalLabel);
        cardPagamento.add(totalWrap);
        cardPagamento.add(Box.createVerticalStrut(compactMode() ? 6 : 12));
        cardPagamento.add(sectionTitlePdv("\uD83D\uDCB3  Forma de Pagamento"));
        JLabel lFormaPg = label("Forma de pagamento");
        lFormaPg.setAlignmentX(Component.LEFT_ALIGNMENT);
        cardPagamento.add(lFormaPg);
        cardPagamento.add(formaPagamento);
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
        lblValorRecebido.setAlignmentX(Component.LEFT_ALIGNMENT);
        cardPagamento.add(lblValorRecebido);
        valorRecebido.setAlignmentX(Component.LEFT_ALIGNMENT);
        valorRecebido.setMaximumSize(new Dimension(Integer.MAX_VALUE, valorRecebido.getPreferredSize().height));
        cardPagamento.add(valorRecebido);
        cardPagamento.add(resumoSubtotal);
        cardPagamento.add(resumoDescontosLinha);
        cardPagamento.add(resumoTotal);
        cardPagamento.add(resumoTroco);
        cardPagamento.add(finalizar);
        cardPagamento.add(Box.createVerticalStrut(compactMode() ? 3 : 5));
        cardPagamento.add(cancelarVendaBtn);
        cardPagamento.add(Box.createVerticalStrut(compactMode() ? 5 : 7));
        cardPagamento.add(limpar);
        // Cancelar item (F7) e cupom fiscal (F10) ficam nos chips do topo do PDV.

        right.add(cardAdicionar);
        right.add(Box.createVerticalStrut(compactMode() ? 8 : 12));
        right.add(cardPagamento);
        final boolean[] valorRecebidoProgramatico = {false};
        Runnable sugerirValorRecebido = () -> {
            // Apos registrar quanto o cliente entregou em dinheiro (Enter),
            // nao sobrescreve o campo com "o que falta" ao sair do foco: o
            // operador precisa ver o valor entregue (ex.: 60) ate finalizar
            // ou editar manualmente. Ver dinheiroRecebidoInformado em addPagamento.
            if (dinheiroRecebidoInformado[0].compareTo(BigDecimal.ZERO) > 0) {
                return;
            }
            BigDecimal total = cartTotalLiquido().max(BigDecimal.ZERO);
            BigDecimal recebidoAplicado = pagamentosDigitados.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal faltante = total.subtract(recebidoAplicado).max(BigDecimal.ZERO);
            valorRecebidoProgramatico[0] = true;
            try {
                valorRecebido.setText(moneyInputText(faltante));
                valorRecebido.selectAll();
            } finally {
                valorRecebidoProgramatico[0] = false;
            }
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
            // Inclui o que esta sendo digitado no campo (antes de "Adicionar pagamento")
            // para o troco acompanhar em tempo real.
            BigDecimal digitandoDinheiro = BigDecimal.ZERO;
            if (valorRecebido.isVisible()) {
                try {
                    digitandoDinheiro = money(valorRecebido.getText());
                } catch (Exception ignored) {
                    digitandoDinheiro = BigDecimal.ZERO;
                }
            }
            // Soma ja quitada por PIX/cartao/convenio etc. sem contar o slot DINHEIRO
            // (o dinheiro aplicado sai e volta "visual" abaixo).
            BigDecimal recebidoSemDinheiro = recebidoAplicado.subtract(dinheiroAplicado);
            // Quanto ainda falta antes de considerar o que o operador digita em dinheiro.
            // Se ja esta zerado (ex.: PIX ja cobriu tudo), NAO somamos o valor digitado
            // no campo de dinheiro — senao o "recebido" infla e o "troco" vira o proprio
            // valor digitado (bug reportado no PDV).
            BigDecimal faltaAntesDoDigitadoEmDinheiro = total.subtract(recebidoSemDinheiro).max(BigDecimal.ZERO);
            BigDecimal dinheiroVisualBase = dinheiroRecebidoInformado[0].max(dinheiroAplicado);
            BigDecimal dinheiroVisual = faltaAntesDoDigitadoEmDinheiro.signum() > 0
                    ? dinheiroVisualBase.max(digitandoDinheiro)
                    : dinheiroVisualBase;
            BigDecimal recebidoVisual = recebidoAplicado.subtract(dinheiroAplicado).add(dinheiroVisual);
            BigDecimal trocoCliente = recebidoVisual.subtract(total).max(BigDecimal.ZERO);

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
                resumoTroco.setText("Troco para o cliente: " + moneyText(trocoCliente));
            }
            if (valorRecebido.isVisible() && !valorRecebido.isFocusOwner()) {
                sugerirValorRecebido.run();
            }
        };
        valorRecebido.getDocument().addDocumentListener(new DocumentListener() {
            private void tick() {
                if (valorRecebidoProgramatico[0]) return;
                paymentSummaryUpdater.run();
            }
            @Override public void insertUpdate(DocumentEvent e) { tick(); }
            @Override public void removeUpdate(DocumentEvent e) { tick(); }
            @Override public void changedUpdate(DocumentEvent e) { tick(); }
        });
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
                if ("DINHEIRO".equals(forma)) {
                    valorRecebidoProgramatico[0] = true;
                    try {
                        valorRecebido.setText(moneyInputText(valor));
                    } finally {
                        valorRecebidoProgramatico[0] = false;
                    }
                } else {
                    valorRecebido.setText(moneyInputText(BigDecimal.ZERO));
                }
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
            pdvEstoqueAutorizadoPorAdminIds.clear();
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
                Map<String, Object> caixaAtual = caixaSel == null ? null : one(
                        "select status, abertura_valor, abertura_timestamp from caixas where id=?",
                        caixaSel.id);
                boolean aberto = caixaAtual != null && "ABERTO".equals(String.valueOf(caixaAtual.get("status")));
                String desdeAbertura = null;
                if (aberto && caixaAtual != null) {
                    desdeAbertura = effectiveDesdeTurno(caixaAtual.get("abertura_timestamp"));
                }
                add.setEnabled(aberto);
                add.setToolTipText(aberto ? null : "Abra o caixa para adicionar itens.");
                if (aberto) {
                    BigDecimal abertura = money(caixaAtual.get("abertura_valor").toString());
                    BigDecimal dinheiroVendas = cashSalesPdvWindow(caixaSel.id, desdeAbertura);
                    BigDecimal suprimentoHoje = BigDecimal.ZERO;
                    BigDecimal sangriaHoje = BigDecimal.ZERO;
                    try {
                        suprimentoHoje = caixaOperacoesPdvWindow(caixaSel.id, "SUPRIMENTO", desdeAbertura);
                        sangriaHoje = caixaOperacoesPdvWindow(caixaSel.id, "SANGRIA", desdeAbertura);
                    } catch (Exception ignored) {
                        // Mantem zeros se a consulta falhar; o restante do PDV segue funcional.
                    }
                    BigDecimal saldoCaixa = abertura.add(dinheiroVendas).add(suprimentoHoje).subtract(sangriaHoje);
                    fundo.setText(moneyInputText(saldoCaixa));
                    String periodoLabel = desdeAbertura != null ? "desde abertura do caixa" : "hoje";
                    StringBuilder tt = new StringBuilder("<html>Abertura: R$ ").append(moneyInputText(abertura))
                            .append("<br>Dinheiro em vendas (").append(periodoLabel).append("): R$ ").append(moneyInputText(dinheiroVendas));
                    if (suprimentoHoje.signum() > 0) {
                        tt.append("<br>Suprimento (").append(periodoLabel).append("): R$ ").append(moneyInputText(suprimentoHoje));
                    }
                    if (sangriaHoje.signum() > 0) {
                        tt.append("<br>Sangria (").append(periodoLabel).append("): R$ ").append(moneyInputText(sangriaHoje));
                    }
                    tt.append("<br><b>Saldo em dinheiro no caixa: R$ ").append(moneyInputText(saldoCaixa)).append("</b></html>");
                    fundo.setToolTipText(tt.toString());
                    fundo.setEditable(false);
                    fundo.setEnabled(false);
                    abrir.setText("Caixa aberto");
                } else {
                    fundo.setEditable(true);
                    fundo.setEnabled(true);
                    fundo.setToolTipText("Valor de abertura (fundo de troco). Ex: 100,00");
                    abrir.setText("Abrir caixa");
                }
                if (caixaSel != null) {
                    String desdeTopo = aberto ? desdeAbertura : null;
                    String periodoTopo = aberto && desdeTopo != null ? "desde abertura" : "hoje";
                    BigDecimal cartaoHoje = cardSalesPdvWindow(caixaSel.id, desdeTopo);
                    cartaoTopo.setText(moneyInputText(cartaoHoje));
                    cartaoTopo.setToolTipText("Total em DEBITO + CREDITO neste caixa (" + periodoTopo + ").");
                    BigDecimal pixHoje = pixSalesPdvWindow(caixaSel.id, desdeTopo);
                    pixTopo.setText(moneyInputText(pixHoje));
                    pixTopo.setToolTipText("Total em PIX neste caixa (" + periodoTopo + ").");
                } else {
                    cartaoTopo.setText("0,00");
                    cartaoTopo.setToolTipText(null);
                    pixTopo.setText("0,00");
                    pixTopo.setToolTipText(null);
                }
            } catch (Exception ex) {
                add.setEnabled(false);
                add.setToolTipText("Abra o caixa para adicionar itens.");
                fundo.setEditable(true);
                fundo.setEnabled(true);
                abrir.setText("Abrir caixa");
                cartaoTopo.setText("0,00");
                pixTopo.setText("0,00");
            }
        };
        caixaCombo.addActionListener(e -> {
            rememberPdvCaixaSelection();
            refreshAddState.run();
        });
        refreshAddState.run();
        // Expoe o refresh do cabecalho para que outras partes do PDV
        // (finalizar venda, sangria, etc) possam atualizar os totais de
        // Fundo (dinheiro), Cartao e PIX em tempo real (turno desde abertura,
        // nao cortando na meia-noite).
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
        bindShortcut(panel, "suprimentoCaixa", KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), () -> caixaOperacao("SUPRIMENTO"));
        bindShortcut(panel, "sangriaCaixa", KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), () -> caixaOperacao("SANGRIA"));
        bindShortcut(cartTable, "cancelarItemTabela", KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), cancelarItem::doClick);
        attachChipAction(chipSuprimento, () -> caixaOperacao("SUPRIMENTO"));
        attachChipAction(chipSangria, () -> caixaOperacao("SANGRIA"));
        attachChipAction(chipFinalizar, finalizar::doClick);
        attachChipAction(chipLimpar, limpar::doClick);
        attachChipAction(chipCancelar, cancelarItem::doClick);
        chipCancelar.setToolTipText("F7 - Cancelar item: selecione a linha no carrinho (esquerda) e clique aqui ou use F7.");
        attachChipAction(chipCupom, comprovante::doClick);
        chipCupom.setToolTipText("F10 - Reemitir cupom fiscal / comprovante.");
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
        split.setResizeWeight(compactMode() ? 0.70 : (uiReferenceSize.width <= 1366 ? 0.62 : 0.68));
        split.setDividerLocation(compactMode() ? 0.72 : (uiReferenceSize.width <= 1366 ? 0.60 : 0.67));
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);
        panel.add(split, BorderLayout.CENTER);
        SwingUtilities.invokeLater(codigo::requestFocus);
        pdvFormaPagamentoRoot = formaPagamento;
        pdvFinalizarButton = finalizar;
        pdvCodigoField = codigo;
        SwingUtilities.invokeLater(this::refreshCart);
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
                refreshEstoqueViews();
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
                refreshEstoqueViews();
            } catch (Exception ex) { error(ex); }
        });

        JButton ajuste = button("Ajustar estoque");
        ajuste.addActionListener(e -> ajustarEstoque());
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

        JPanel actions = new JPanel(new GridLayout(1, 2, 8, 8));
        actions.setOpaque(false);
        actions.add(ajuste);
        actions.add(perdas);

        JPanel tablesGrid = new JPanel(new GridBagLayout());
        tablesGrid.setOpaque(false);
        GridBagConstraints tg = new GridBagConstraints();
        tg.fill = GridBagConstraints.BOTH;
        tg.weightx = 1.0;
        tg.gridx = 0;
        tg.gridwidth = 2;
        tg.insets = new Insets(0, 0, 12, 0);
        tg.gridy = 0;
        tg.weighty = 5.0;
        JTable tabelaProdutos = buildEstoqueProdutosTable();
        JPanel produtosCard = new JPanel(new BorderLayout(0, 8));
        produtosCard.setOpaque(false);
        JPanel buscaProdutosPanel = new JPanel(new BorderLayout(8, 0));
        buscaProdutosPanel.setOpaque(false);
        JTextField buscaProdutos = createPlaceholderField("Buscar por produto, codigo, SKU, categoria, marca, fornecedor...");
        stylePdvField(buscaProdutos);
        estoqueProdutosSearchField = buscaProdutos;
        JLabel buscaProdutosStatus = new JLabel(" ");
        buscaProdutosStatus.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
        buscaProdutosStatus.setForeground(TEXT_MUTED);
        estoqueProdutosSearchStatusLabel = buscaProdutosStatus;
        JButton limparBuscaProdutos = button("Limpar");
        limparBuscaProdutos.addActionListener(e -> {
            buscaProdutos.setText("");
            buscaProdutos.requestFocusInWindow();
        });
        buscaProdutosPanel.add(buscaProdutos, BorderLayout.CENTER);
        buscaProdutosPanel.add(limparBuscaProdutos, BorderLayout.EAST);
        JPanel buscaProdutosWrap = new JPanel(new BorderLayout(0, 4));
        buscaProdutosWrap.setOpaque(false);
        buscaProdutosWrap.add(buscaProdutosPanel, BorderLayout.CENTER);
        buscaProdutosWrap.add(buscaProdutosStatus, BorderLayout.SOUTH);
        produtosCard.add(buscaProdutosWrap, BorderLayout.NORTH);
        installEstoqueProdutosSmartSearch(tabelaProdutos, buscaProdutos, buscaProdutosStatus);
        JScrollPane scrollProdutos = new JScrollPane(tabelaProdutos);
        scrollProdutos.setBorder(BorderFactory.createLineBorder(BORDER_SOFT));
        scrollProdutos.getViewport().setBackground(PANEL_BG);
        produtosCard.add(scrollProdutos, BorderLayout.CENTER);
        JPanel linhaProdAcoes = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        linhaProdAcoes.setOpaque(false);
        JButton editarProduto = button("Editar produto");
        editarProduto.setToolTipText("Selecione um produto na tabela (ou d\u00EA duplo clique) para alterar dados, precos e fiscal.");
        editarProduto.addActionListener(e -> editarProdutoSelecionadoNaGrade(tabelaProdutos));
        linhaProdAcoes.add(editarProduto);
        produtosCard.add(linhaProdAcoes, BorderLayout.SOUTH);
        tablesGrid.add(section("Produtos", produtosCard), tg);
        tg.gridy = 1;
        tg.weighty = 1.5;
        tablesGrid.add(section("Movimentacoes recentes", table("""
            select m.id as ID, m.produto_id as ProdutoID, p.nome as Produto, m.tipo as Tipo, m.quantidade as Quantidade,
                   m.timestamp as DataHora, m.observacao as Observacao
            from movimentacao_estoque m join produtos p on p.id = m.produto_id
            order by m.id desc limit 40
            """)), tg);
        tg.gridy = 2;
        tg.weighty = 1.0;
        tg.gridwidth = 1;
        tg.insets = new Insets(0, 0, 0, 6);
        tg.gridx = 0;
        tablesGrid.add(section("Histórico de preço", table("""
            select h.id as ID, h.produto_id as ProdutoID, p.nome as Produto, h.preco_custo_anterior as CustoAnterior,
                   h.preco_custo_novo as CustoNovo, h.preco_venda_anterior as VendaAnterior, h.preco_venda_novo as VendaNova,
                   h.timestamp as DataHora, h.motivo as Motivo
            from historico_preco h join produtos p on p.id = h.produto_id
            order by h.id desc limit 40
            """)), tg);
        tg.gridx = 1;
        tg.insets = new Insets(0, 6, 0, 0);
        tablesGrid.add(section("Lotes e validades", table("""
            select e.id as Entrada, e.produto_id as ProdutoID, p.nome as Produto, coalesce(e.lote,'-') as Lote,
                   coalesce(e.validade,'-') as Validade, e.quantidade as Quantidade, e.documento as Documento, e.criado_em as EntradaEm
            from entradas_estoque e join produtos p on p.id = e.produto_id
            where e.lote is not null or e.validade is not null
            order by case when e.validade is null then 1 else 0 end, date(e.validade), e.id desc
            limit 40
            """)), tg);

        panel.add(formsGrid);
        panel.add(Box.createVerticalStrut(12));
        panel.add(actions);
        panel.add(Box.createVerticalStrut(12));
        panel.add(tablesGrid);
        return panel;
    }

    /**
     * Modelo da grade "Produtos" na aba Estoque (colunas alinhadas ao cadastro).
     * Usado na montagem inicial e apos vendas para refletir {@code produtos.estoque_atual}.
     */
    private DefaultTableModel newEstoqueProdutosTableModel() throws Exception {
        List<Map<String, Object>> data = rows("""
                select p.id as ID,
                       coalesce(p.codigo_interno, '') as Interno,
                       p.nome as Produto,
                       coalesce(p.codigo_barras, '') as Barras,
                       coalesce(p.sku, '') as SKU,
                       coalesce(p.marca, '') as Marca,
                       coalesce(p.fabricante, '') as Fabricante,
                       coalesce(p.categoria, '') as Categoria,
                       coalesce(p.unidade, '') as Unidade,
                       coalesce(p.ncm, '') as NCM,
                       coalesce(p.cest, '') as CEST,
                       coalesce(p.localizacao, '') as Local,
                       p.estoque_atual as Estoque,
                       p.estoque_minimo as Minimo,
                       p.preco_custo as Custo,
                       p.preco_venda as Venda,
                       coalesce(p.validade, '') as Validade,
                       coalesce(p.lote_padrao, '') as Lote,
                       coalesce(p.observacoes, '') as Obs,
                       coalesce(nullif(trim(f.nome_fantasia), ''), nullif(trim(f.razao_social), ''), '') as Fornecedor,
                       coalesce(p.imagem_url, '') as _imagem_url,
                       p.ativo as Ativo,
                       p.controla_lote as ControlaLote
                from produtos p
                left join fornecedores f on f.id = p.fornecedor_id
                order by p.nome
                """);
        Vector<String> colNames = new Vector<>();
        colNames.add("ID");
        colNames.add("Interno");
        colNames.add("Produto");
        colNames.add("Barras");
        colNames.add("SKU");
        colNames.add("Marca");
        colNames.add("Fabricante");
        colNames.add("Categoria");
        colNames.add("Unidade");
        colNames.add("NCM");
        colNames.add("CEST");
        colNames.add("Local");
        colNames.add("Estoque");
        colNames.add("Minimo");
        colNames.add("Custo");
        colNames.add("Venda");
        colNames.add("Validade");
        colNames.add("Lote");
        colNames.add("Obs");
        colNames.add("Fornecedor");
        colNames.add("Ativo");
        colNames.add("Ctl.Lote");
        colNames.add(ESTOQUE_PROD_COL_IMAGEM_URL);

        DefaultTableModel model = new DefaultTableModel(colNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return Object.class;
            }
        };
        for (Map<String, Object> row : data) {
            String url = strCampo(row.get(ESTOQUE_PROD_COL_IMAGEM_URL)).trim();
            BigDecimal est = money(String.valueOf(row.getOrDefault("Estoque", "0")));
            BigDecimal min = money(String.valueOf(row.getOrDefault("Minimo", "0")));
            BigDecimal custo = money(String.valueOf(row.getOrDefault("Custo", "0")));
            BigDecimal venda = money(String.valueOf(row.getOrDefault("Venda", "0")));
            Object ativo = row.get("Ativo");
            Object ctl = row.get("ControlaLote");
            model.addRow(new Object[]{
                    row.get("ID"),
                    row.get("Interno"),
                    strCampo(row.get("Produto")),
                    strCampo(row.get("Barras")),
                    strCampo(row.get("SKU")),
                    strCampo(row.get("Marca")),
                    strCampo(row.get("Fabricante")),
                    strCampo(row.get("Categoria")),
                    strCampo(row.get("Unidade")),
                    strCampo(row.get("NCM")),
                    strCampo(row.get("CEST")),
                    strCampo(row.get("Local")),
                    BR_NUMBER.format(est),
                    BR_NUMBER.format(min),
                    moneyText(custo),
                    moneyText(venda),
                    strCampo(row.get("Validade")),
                    strCampo(row.get("Lote")),
                    strCampo(row.get("Obs")),
                    strCampo(row.get("Fornecedor")),
                    ativo,
                    ctl,
                    url
            });
        }
        return model;
    }

    private void configureEstoqueProdutosTableView(JTable table) {
        styleTable(table);
        // Grade densa (mesma base do styleTable): linhas mais baixas que a versao "ampla" antiga.
        int rh = compactMode() ? 28 : 34;
        table.setRowHeight(rh);
        table.setIntercellSpacing(new Dimension(3, 1));
        table.setAutoCreateRowSorter(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        if (table.getModel() instanceof DefaultTableModel m) {
            int urlCol = m.findColumn(ESTOQUE_PROD_COL_IMAGEM_URL);
            if (urlCol >= 0) {
                TableColumn urlColumn = table.getColumnModel().getColumn(urlCol);
                urlColumn.setMinWidth(0);
                urlColumn.setMaxWidth(0);
                urlColumn.setWidth(0);
                urlColumn.setPreferredWidth(0);
            }
        }
    }

    private void installEstoqueProdutosSmartSearch(JTable table, JTextField search, JLabel status) {
        if (!(table.getModel() instanceof DefaultTableModel model)) {
            return;
        }
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        if (!Boolean.TRUE.equals(search.getClientProperty("estoque.smartSearch.installed"))) {
            search.putClientProperty("estoque.smartSearch.installed", Boolean.TRUE);
            search.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { applyEstoqueProdutosSearchFilter(table, search, status); }
                @Override public void removeUpdate(DocumentEvent e) { applyEstoqueProdutosSearchFilter(table, search, status); }
                @Override public void changedUpdate(DocumentEvent e) { applyEstoqueProdutosSearchFilter(table, search, status); }
            });
            search.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "selectFirstProduct");
            search.getActionMap().put("selectFirstProduct", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    selectFirstEstoqueProdutoResult(table);
                }
            });
            search.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearProductSearch");
            search.getActionMap().put("clearProductSearch", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    search.setText("");
                }
            });
        }
        applyEstoqueProdutosSearchFilter(table, search, status);
    }

    private void applyEstoqueProdutosSearchFilter(JTable table, JTextField search, JLabel status) {
        if (!(table.getRowSorter() instanceof TableRowSorter<?> rawSorter)) {
            return;
        }
        @SuppressWarnings("unchecked")
        TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) rawSorter;
        List<String> terms = normalizedSearchTerms(search.getText());
        if (terms.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(new RowFilter<>() {
                @Override
                public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                    StringBuilder searchable = new StringBuilder();
                    for (int i = 0; i < entry.getValueCount(); i++) {
                        String colName = entry.getModel().getColumnName(i);
                        if (ESTOQUE_PROD_COL_IMAGEM_URL.equals(colName)) {
                            continue;
                        }
                        Object value = entry.getValue(i);
                        if (value != null) {
                            searchable.append(' ').append(value);
                        }
                    }
                    String haystack = normalizeSearchText(searchable.toString());
                    return terms.stream().allMatch(haystack::contains);
                }
            });
        }
        updateEstoqueProdutosSearchStatus(table, search, status);
    }

    private List<String> normalizedSearchTerms(String query) {
        String normalized = normalizeSearchText(query);
        if (normalized.isBlank()) {
            return List.of();
        }
        return Arrays.stream(normalized.split("\\s+"))
                .filter(term -> !term.isBlank())
                .toList();
    }

    private String normalizeSearchText(String value) {
        if (value == null) {
            return "";
        }
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private void updateEstoqueProdutosSearchStatus(JTable table, JTextField search, JLabel status) {
        if (status == null) {
            return;
        }
        int total = table.getModel().getRowCount();
        int found = table.getRowCount();
        if (search.getText().trim().isBlank()) {
            status.setText(total + " produtos cadastrados. Digite para filtrar; Enter seleciona o primeiro resultado.");
        } else {
            status.setText(found + " de " + total + " produtos encontrados. Enter seleciona o primeiro resultado; duplo clique edita.");
        }
        if (found > 0 && table.getSelectedRow() < 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private void selectFirstEstoqueProdutoResult(JTable table) {
        if (table.getRowCount() == 0) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        table.requestFocusInWindow();
        table.setRowSelectionInterval(0, 0);
        table.scrollRectToVisible(table.getCellRect(0, 0, true));
    }

    /** Recarrega a grade de produtos da aba Estoque a partir do banco (ex.: apos uma venda no PDV). */
    private void refreshEstoqueProdutosTableFromDb() {
        JTable t = estoqueProdutosTable;
        if (t == null) {
            return;
        }
        try {
            DefaultTableModel model = newEstoqueProdutosTableModel();
            t.setModel(model);
            configureEstoqueProdutosTableView(t);
            if (estoqueProdutosSearchField != null) {
                installEstoqueProdutosSmartSearch(t, estoqueProdutosSearchField, estoqueProdutosSearchStatusLabel);
            }
        } catch (Exception e) {
            appLog("WARN", "ESTOQUE_REFRESH", "Falha ao atualizar grade de produtos apos venda", e.getMessage());
        }
    }

    private void refreshEstoqueViews() {
        refreshEstoqueProdutosTableFromDb();
        SwingUtilities.invokeLater(this::refreshDashboardPainelWidgets);
    }

    /**
     * Lista de produtos no estoque com colunas alinhadas ao cadastro no banco.
     */
    private JTable buildEstoqueProdutosTable() {
        try {
            DefaultTableModel model = newEstoqueProdutosTableModel();
            JTable table = new JTable(model);
            estoqueProdutosTable = table;
            configureEstoqueProdutosTableView(table);
            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        editarProdutoSelecionadoNaGrade(table);
                    }
                }
            });
            return table;
        } catch (Exception e) {
            error(e);
            estoqueProdutosTable = null;
            return new JTable();
        }
    }

    private void editarProdutoSelecionadoNaGrade(JTable tabelaProdutos) {
        try {
            requireInventoryAccess();
        } catch (Exception ex) {
            error(ex);
            return;
        }
        int viewRow = tabelaProdutos.getSelectedRow();
        if (viewRow < 0) {
            JOptionPane.showMessageDialog(frame, "Selecione um produto na grade antes de editar.",
                    "Editar produto", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int modelRow = tabelaProdutos.convertRowIndexToModel(viewRow);
        DefaultTableModel model = (DefaultTableModel) tabelaProdutos.getModel();
        int colId = model.findColumn("ID");
        if (colId < 0) {
            JOptionPane.showMessageDialog(frame, "Grade de produtos sem coluna ID.", "Editar produto", JOptionPane.ERROR_MESSAGE);
            return;
        }
        Object idObj = model.getValueAt(modelRow, colId);
        if (idObj == null) {
            JOptionPane.showMessageDialog(frame, "Nao foi possivel identificar o produto.", "Editar produto", JOptionPane.WARNING_MESSAGE);
            return;
        }
        long produtoId = ((Number) idObj).longValue();
        abrirDialogEditarProduto(produtoId);
    }

    private BigDecimal parseMargemPercentual(String s) {
        if (s == null || s.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        String raw = s.trim().replace("%", "").replace(" ", "").replace(",", ".");
        if (raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(raw);
    }

    private String formatMargemPercentual(BigDecimal custo, BigDecimal venda) {
        if (custo == null || custo.compareTo(BigDecimal.ZERO) <= 0) {
            return "0";
        }
        return venda.subtract(custo).divide(custo, 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .stripTrailingZeros()
                .toPlainString();
    }

    /**
     * Cadastro completo do produto em abas (basico, preco, fiscal, fornecedor), no estilo ERP.
     */
    private void abrirDialogEditarProduto(long produtoId) {
        Map<String, Object> row;
        try {
            row = one("""
                    select p.id, p.codigo_interno, p.nome, p.codigo_barras, p.sku, p.categoria, p.unidade,
                           p.marca, p.fabricante, p.localizacao, p.validade, p.lote_padrao, p.observacoes, p.imagem_url,
                           p.ncm, p.cest, p.preco_custo, p.preco_venda, p.estoque_atual, p.estoque_minimo,
                           p.fornecedor_id, p.ativo, p.controla_lote, p.permite_preco_zero
                    from produtos p where p.id = ?
                    """, produtoId);
        } catch (Exception ex) {
            error(ex);
            return;
        }
        if (row == null) {
            JOptionPane.showMessageDialog(frame, "Produto nao encontrado.", "Editar produto", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JDialog dialog = new JDialog(frame, "Editar produto", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.getContentPane().setBackground(PANEL_BG);
        dialog.setSize(scale(760), scale(560));
        dialog.setMinimumSize(new Dimension(scale(560), scale(420)));
        dialog.setLocationRelativeTo(frame);

        JTextField codigoInterno = new JTextField(strCampo(row.get("codigo_interno")));
        codigoInterno.setEditable(false);
        codigoInterno.setBackground(new Color(0xF0, 0xF0, 0xF0));
        JTextField barras = new JTextField(strCampo(row.get("codigo_barras")));
        JTextField sku = new JTextField(strCampo(row.get("sku")));
        JTextField nome = new JTextField(strCampo(row.get("nome")));
        JTextField categoria = new JTextField(strCampo(row.get("categoria")));
        JTextField unidade = new JTextField(strCampo(row.get("unidade")));
        JTextField marca = new JTextField(strCampo(row.get("marca")));
        JTextField fabricante = new JTextField(strCampo(row.get("fabricante")));
        JTextField local = new JTextField(strCampo(row.get("localizacao")));
        JTextField validade = new JTextField(strCampo(row.get("validade")));
        JTextField lotePadrao = new JTextField(strCampo(row.get("lote_padrao")));
        JTextField imagemUrl = new JTextField(strCampo(row.get("imagem_url")));
        JTextArea observacoes = new JTextArea(4, 44);
        observacoes.setLineWrap(true);
        observacoes.setWrapStyleWord(true);
        observacoes.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        observacoes.setText(strCampo(row.get("observacoes")));
        BigDecimal estAtual = money(String.valueOf(row.getOrDefault("estoque_atual", "0")));
        JLabel estoqueAtual = new JLabel(BR_NUMBER.format(estAtual));
        estoqueAtual.setFont(new Font("Segoe UI", Font.BOLD, fontSize(12)));
        JTextField minimo = new JTextField(moneyInputText(money(String.valueOf(row.getOrDefault("estoque_minimo", "0")))));
        JCheckBox ativo = new JCheckBox("Ativo", intValue(row.get("ativo")) != 0);
        JCheckBox controlaLote = new JCheckBox("Controla lote", intValue(row.get("controla_lote")) != 0);
        JCheckBox permitePrecoZero = new JCheckBox("Permite preco zero no PDV", intValue(row.get("permite_preco_zero")) != 0);
        ativo.setOpaque(false);
        controlaLote.setOpaque(false);
        permitePrecoZero.setOpaque(false);

        JPanel tabBasico = new JPanel(new GridBagLayout());
        tabBasico.setOpaque(false);
        int r = 0;
        addCompactRow(tabBasico, r++, "Codigo interno", codigoInterno, 10, "Codigo barras", barras, 14);
        addCompactRow(tabBasico, r++, "SKU", sku, 10, "Unidade", unidade, 6);
        addCompactRow(tabBasico, r++, "Descrição (nome)", nome, 36);
        addCompactRow(tabBasico, r++, "Grupo (categoria)", categoria, 18, "Marca", marca, 14);
        addCompactRow(tabBasico, r++, "Fabricante", fabricante, 22, "Prateleira (local)", local, 14);
        addCompactRow(tabBasico, r++, "Validade AAAA-MM-DD", validade, 12, "Lote padrao", lotePadrao, 12);
        addCompactRow(tabBasico, r++, "URL da imagem", imagemUrl, 40);
        addCompactRow(tabBasico, r++, "Estoque atual (somente leitura)", estoqueAtual, 8, "Estoque minimo", minimo, 10);
        addCompactRow(tabBasico, r++, "Ativo", ativo, 4, "Controla lote", controlaLote, 4);
        addCompactRow(tabBasico, r++, "Permite preco zero", permitePrecoZero, 4);
        JScrollPane obsScroll = new JScrollPane(observacoes);
        obsScroll.setBorder(BorderFactory.createLineBorder(BORDER_SOFT));
        addCompactRow(tabBasico, r++, "Observacoes", obsScroll, 1);

        BigDecimal custoIni = money(String.valueOf(row.getOrDefault("preco_custo", "0")));
        BigDecimal vendaIni = money(String.valueOf(row.getOrDefault("preco_venda", "0")));
        JTextField custo = new JTextField(moneyInputText(custoIni));
        JTextField margem = new JTextField(formatMargemPercentual(custoIni, vendaIni));
        JTextField venda = new JTextField(moneyInputText(vendaIni));
        JPanel tabPreco = new JPanel(new GridBagLayout());
        tabPreco.setOpaque(false);
        addCompactRow(tabPreco, 0, "Preco de custo", custo, 12, "Margem (%)", margem, 10);
        addCompactRow(tabPreco, 1, "Preco de venda", venda, 14);
        JLabel dicaPreco = new JLabel("Altere custo ou margem para recalcular a venda; altere a venda para recalcular a margem.");
        dicaPreco.setFont(new Font("Segoe UI", Font.ITALIC, fontSize(11)));
        dicaPreco.setForeground(TEXT_MUTED);
        GridBagConstraints gcp = new GridBagConstraints();
        gcp.gridx = 0;
        gcp.gridy = 2;
        gcp.gridwidth = 5;
        gcp.insets = new Insets(6, 4, 2, 6);
        gcp.anchor = GridBagConstraints.WEST;
        tabPreco.add(dicaPreco, gcp);

        final boolean[] ignoraPreco = {false};
        final BigDecimal bd100 = new BigDecimal("100");
        DocumentListener custoMargemListener = new DocumentListener() {
            private void tick() {
                if (ignoraPreco[0]) {
                    return;
                }
                ignoraPreco[0] = true;
                try {
                    BigDecimal c = money(custo.getText());
                    BigDecimal mPct = parseMargemPercentual(margem.getText());
                    BigDecimal v = c.multiply(BigDecimal.ONE.add(mPct.divide(bd100, 12, RoundingMode.HALF_UP)))
                            .setScale(2, RoundingMode.HALF_UP);
                    venda.setText(moneyInputText(v));
                } catch (Exception ignored) {
                    // mantem texto enquanto usuario digita
                } finally {
                    ignoraPreco[0] = false;
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                tick();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                tick();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                tick();
            }
        };
        DocumentListener vendaListener = new DocumentListener() {
            private void tick() {
                if (ignoraPreco[0]) {
                    return;
                }
                ignoraPreco[0] = true;
                try {
                    BigDecimal c = money(custo.getText());
                    BigDecimal v = money(venda.getText());
                    margem.setText(formatMargemPercentual(c, v));
                } catch (Exception ignored) {
                } finally {
                    ignoraPreco[0] = false;
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                tick();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                tick();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                tick();
            }
        };
        custo.getDocument().addDocumentListener(custoMargemListener);
        margem.getDocument().addDocumentListener(custoMargemListener);
        venda.getDocument().addDocumentListener(vendaListener);

        JTextField ncm = new JTextField(strCampo(row.get("ncm")));
        JTextField cest = new JTextField(strCampo(row.get("cest")));
        JPanel tabFiscal = new JPanel(new GridBagLayout());
        tabFiscal.setOpaque(false);
        addCompactRow(tabFiscal, 0, "NCM", ncm, 14, "CEST", cest, 14);
        JLabel dicaFiscal = new JLabel("Informacoes fiscais usadas em NF-e e conferencia.");
        dicaFiscal.setFont(new Font("Segoe UI", Font.ITALIC, fontSize(11)));
        dicaFiscal.setForeground(TEXT_MUTED);
        GridBagConstraints gcf = new GridBagConstraints();
        gcf.gridx = 0;
        gcf.gridy = 1;
        gcf.gridwidth = 5;
        gcf.insets = new Insets(8, 4, 2, 6);
        gcf.anchor = GridBagConstraints.WEST;
        tabFiscal.add(dicaFiscal, gcf);

        JComboBox<Item> fornecedorCombo = new JComboBox<>();
        fornecedorCombo.addItem(new Item(-1L, "Nenhum"));
        try {
            for (Map<String, Object> fr : rows("""
                    select id, trim(razao_social) as razao, trim(coalesce(nome_fantasia,'')) as fantasia
                    from fornecedores order by razao_social
                    """)) {
                long fid = ((Number) fr.get("id")).longValue();
                String rz = strCampo(fr.get("razao"));
                String fn = strCampo(fr.get("fantasia"));
                String label = fn.isEmpty() ? rz : fn;
                if (label.isEmpty()) {
                    label = "Fornecedor #" + fid;
                }
                fornecedorCombo.addItem(new Item(fid, label));
            }
        } catch (Exception ignored) {
        }
        Object fidObj = row.get("fornecedor_id");
        long fidAtual = fidObj instanceof Number ? ((Number) fidObj).longValue() : -1L;
        for (int i = 0; i < fornecedorCombo.getItemCount(); i++) {
            Item it = fornecedorCombo.getItemAt(i);
            if (it.id() == fidAtual) {
                fornecedorCombo.setSelectedIndex(i);
                break;
            }
        }
        JPanel tabFornecedor = new JPanel(new GridBagLayout());
        tabFornecedor.setOpaque(false);
        addCompactRow(tabFornecedor, 0, "Fornecedor padrao", fornecedorCombo, 28);

        JTabbedPane abas = new JTabbedPane();
        abas.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));
        abas.addTab("Informacoes basicas", wrapFormularioFornecedorEmScroll(tabBasico));
        abas.addTab("Preco", wrapFormularioFornecedorEmScroll(tabPreco));
        abas.addTab("Informacoes fiscais", wrapFormularioFornecedorEmScroll(tabFiscal));
        abas.addTab("Fornecedor", wrapFormularioFornecedorEmScroll(tabFornecedor));

        JPanel sul = new JPanel(new GridLayout(1, 2, 8, 0));
        sul.setOpaque(false);
        JButton gravar = button("Gravar");
        JButton cancelar = button("Cancelar");
        gravar.addActionListener(e -> {
            try {
                requireInventoryAccess();
                BusinessRules.requireNotBlank(nome.getText(), "Nome do produto");
                BusinessRules.requireNotBlank(unidade.getText(), "Unidade");
                Item fornSel = (Item) fornecedorCombo.getSelectedItem();
                Long fornId = fornSel != null && fornSel.id() > 0 ? fornSel.id() : null;
                inventoryService.updateProduct(produtoId,
                        new DesktopInventoryService.ProductUpdate(
                                nome.getText().trim(),
                                barras.getText(),
                                sku.getText(),
                                categoria.getText(),
                                unidade.getText().trim(),
                                marca.getText(),
                                fabricante.getText(),
                                money(custo.getText()),
                                money(venda.getText()),
                                money(minimo.getText()),
                                local.getText(),
                                validade.getText(),
                                lotePadrao.getText(),
                                observacoes.getText(),
                                imagemUrl.getText(),
                                ncm.getText(),
                                cest.getText(),
                                fornId,
                                ativo.isSelected(),
                                controlaLote.isSelected(),
                                permitePrecoZero.isSelected()
                        ),
                        user.id);
                audit("PRODUTO_EDITADO", "ID " + produtoId + " | " + nome.getText().trim());
                dialog.setVisible(false);
                refreshEstoqueViews();
            } catch (Exception ex) {
                error(ex);
            }
        });
        cancelar.addActionListener(e -> dialog.dispose());
        sul.add(gravar);
        sul.add(cancelar);

        JPanel centro = new JPanel(new BorderLayout(0, 8));
        centro.setOpaque(false);
        centro.setBorder(new EmptyBorder(8, 12, 4, 12));
        JLabel titulo = new JLabel("Produto #" + produtoId + " — ajuste os campos nas abas e clique em Gravar.");
        titulo.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));
        titulo.setForeground(TEXT_MUTED);
        centro.add(titulo, BorderLayout.NORTH);
        centro.add(abas, BorderLayout.CENTER);

        dialog.add(centro, BorderLayout.CENTER);
        dialog.add(sul, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private static String strCampo(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String nullIfBlank(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Campos completos do cadastro de fornecedor (aba + dialogo de edicao). */
    private static final class FornecedorCampos {
        final JTextField razao = new JTextField();
        final JTextField fantasia = new JTextField();
        final JComboBox<String> tipoDocumento = new JComboBox<>(new String[]{"CNPJ", "CPF"});
        final JTextField cnpj = new JTextField();
        final JTextField inscricaoEstadual = new JTextField();
        final JTextField telefone = new JTextField();
        final JTextField celular = new JTextField();
        final JTextField email = new JTextField();
        final JTextField cep = new JTextField();
        final JTextField endereco = new JTextField();
        final JTextField numero = new JTextField();
        final JTextField bairro = new JTextField();
        final JTextField complemento = new JTextField();
        final JTextField cidade = new JTextField();
        final JTextField estado = new JTextField();
        final JTextField contato = new JTextField();
        final JCheckBox ativo = new JCheckBox("Ativo", true);
        final JTextArea observacoes;

        FornecedorCampos() {
            tipoDocumento.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            ativo.setOpaque(false);
            ativo.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            observacoes = new JTextArea(3, 48);
            observacoes.setLineWrap(true);
            observacoes.setWrapStyleWord(true);
            observacoes.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            observacoes.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        }

        void limpar() {
            razao.setText("");
            fantasia.setText("");
            tipoDocumento.setSelectedItem("CNPJ");
            cnpj.setText("");
            inscricaoEstadual.setText("");
            telefone.setText("");
            celular.setText("");
            email.setText("");
            cep.setText("");
            endereco.setText("");
            numero.setText("");
            bairro.setText("");
            complemento.setText("");
            cidade.setText("");
            estado.setText("");
            contato.setText("");
            ativo.setSelected(true);
            observacoes.setText("");
        }

        void preencherDeLinha(Map<String, Object> row) {
            if (row == null) {
                return;
            }
            razao.setText(strCampo(row.get("razao_social")));
            fantasia.setText(strCampo(row.get("nome_fantasia")));
            String tipo = strCampo(row.get("documento_tipo"));
            if (tipo.isEmpty()) {
                tipo = "CNPJ";
            }
            tipoDocumento.setSelectedItem(tipo.equals("CPF") ? "CPF" : "CNPJ");
            cnpj.setText(strCampo(row.get("cnpj")));
            inscricaoEstadual.setText(strCampo(row.get("inscricao_estadual")));
            telefone.setText(strCampo(row.get("telefone")));
            celular.setText(strCampo(row.get("celular")));
            email.setText(strCampo(row.get("email")));
            cep.setText(strCampo(row.get("cep")));
            endereco.setText(strCampo(row.get("endereco")));
            numero.setText(strCampo(row.get("numero")));
            bairro.setText(strCampo(row.get("bairro")));
            complemento.setText(strCampo(row.get("complemento")));
            cidade.setText(strCampo(row.get("cidade")));
            estado.setText(strCampo(row.get("estado")));
            contato.setText(strCampo(row.get("contato")));
            Object at = row.get("ativo");
            if (at instanceof Number n) {
                ativo.setSelected(n.intValue() != 0);
            } else {
                ativo.setSelected(true);
            }
            observacoes.setText(strCampo(row.get("observacoes")));
        }
    }

    private void montarCamposFornecedorNoFormulario(JPanel form, FornecedorCampos c) {
        Window parentWin = frame != null ? frame : SwingUtilities.getWindowAncestor(form);
        addCompactRow(form, 0, "Razão social", c.razao, 24, "Fantasia", c.fantasia, 20);

        JPanel docLinha = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        docLinha.setOpaque(false);
        limitFieldWidth(c.cnpj, 16);
        JButton btnDoc = styledButton("Consultar", MARKET_GREEN_2);
        btnDoc.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
        btnDoc.setToolTipText("Busca razao social e endereco na BrasilAPI (CNPJ) ou nome (CPF).");
        docLinha.add(c.cnpj);
        docLinha.add(btnDoc);
        btnDoc.addActionListener(ev -> consultarDocumentoFornecedor(c, parentWin));
        addCompactRow(form, 1, "Tipo doc.", c.tipoDocumento, 6, "CPF/CNPJ", docLinha, 20);

        JPanel cepLinha = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        cepLinha.setOpaque(false);
        limitFieldWidth(c.cep, 10);
        JButton btnCep = styledButton("CEP", MARKET_GREEN_2);
        btnCep.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
        btnCep.setToolTipText("Preenche logradouro, bairro, cidade e UF pelo ViaCEP.");
        cepLinha.add(c.cep);
        cepLinha.add(btnCep);
        btnCep.addActionListener(ev -> consultarCepFornecedor(c, parentWin));
        addCompactRow(form, 2, "CEP", cepLinha, 12, "Inscricao estadual", c.inscricaoEstadual, 18);

        addCompactRow(form, 3, "Endereco", c.endereco, 28, "Numero", c.numero, 10);
        addCompactRow(form, 4, "Bairro", c.bairro, 22, "Complemento", c.complemento, 18);
        addCompactRow(form, 5, "Cidade", c.cidade, 20, "UF", c.estado, 4);
        addCompactRow(form, 6, "Telefone", c.telefone, 14, "Celular", c.celular, 14);
        addCompactRow(form, 7, "Email", c.email, 24, "Contato", c.contato, 18);
        addCompactRow(form, 8, "Ativo", c.ativo, 4);
        JScrollPane obsScroll = new JScrollPane(c.observacoes);
        obsScroll.setBorder(BorderFactory.createLineBorder(BORDER_SOFT));
        obsScroll.setPreferredSize(new Dimension(scale(620), scale(92)));
        addCompactRow(form, 9, "Observacoes", obsScroll, 1);
    }

    private static String digitsOnlyFornecedor(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                b.append(ch);
            }
        }
        return b.toString();
    }

    private static String formatCnpjUi(String d14) {
        if (d14.length() != 14) {
            return d14;
        }
        return d14.substring(0, 2) + "." + d14.substring(2, 5) + "." + d14.substring(5, 8)
                + "/" + d14.substring(8, 12) + "-" + d14.substring(12, 14);
    }

    private static String formatCpfUi(String d11) {
        if (d11.length() != 11) {
            return d11;
        }
        return d11.substring(0, 3) + "." + d11.substring(3, 6) + "." + d11.substring(6, 9) + "-" + d11.substring(9, 11);
    }

    private static String formatCepUi(String d8) {
        if (d8.length() != 8) {
            return d8;
        }
        return d8.substring(0, 5) + "-" + d8.substring(5);
    }

    private static String fornecedorJsonText(JsonNode root, String field) {
        JsonNode v = root.get(field);
        if (v == null || v.isNull()) {
            return "";
        }
        return v.asText("").trim();
    }

    private static void aplicarJsonCnpjNasTelasFornecedor(JsonNode root, FornecedorCampos c) {
        c.tipoDocumento.setSelectedItem("CNPJ");
        String cnpjDigits = digitsOnlyFornecedor(fornecedorJsonText(root, "cnpj"));
        if (!cnpjDigits.isEmpty()) {
            c.cnpj.setText(formatCnpjUi(cnpjDigits));
        }
        c.razao.setText(fornecedorJsonText(root, "razao_social"));
        c.fantasia.setText(fornecedorJsonText(root, "nome_fantasia"));
        String cepDigits = digitsOnlyFornecedor(fornecedorJsonText(root, "cep"));
        if (!cepDigits.isEmpty()) {
            c.cep.setText(formatCepUi(cepDigits));
        }
        String tipoLog = fornecedorJsonText(root, "descricao_tipo_de_logradouro");
        String logr = fornecedorJsonText(root, "logradouro");
        if (!tipoLog.isEmpty() && !logr.isEmpty()) {
            c.endereco.setText(tipoLog + " " + logr);
        } else {
            c.endereco.setText(logr);
        }
        c.numero.setText(fornecedorJsonText(root, "numero"));
        c.complemento.setText(fornecedorJsonText(root, "complemento"));
        c.bairro.setText(fornecedorJsonText(root, "bairro"));
        c.cidade.setText(fornecedorJsonText(root, "municipio"));
        c.estado.setText(fornecedorJsonText(root, "uf"));
        String tel = fornecedorJsonText(root, "ddd_telefone_1");
        if (tel.isEmpty()) {
            tel = fornecedorJsonText(root, "ddd_telefone_2");
        }
        c.telefone.setText(tel);
        String mail = fornecedorJsonText(root, "email");
        if (!mail.isEmpty()) {
            c.email.setText(mail);
        }
        String situacao = fornecedorJsonText(root, "descricao_situacao_cadastral");
        if (!situacao.isEmpty() && !"ATIVA".equalsIgnoreCase(situacao)) {
            c.ativo.setSelected(false);
        }
    }

    private static void aplicarJsonCpfNasTelasFornecedor(JsonNode root, FornecedorCampos c) {
        c.tipoDocumento.setSelectedItem("CPF");
        String cpfDigits = digitsOnlyFornecedor(fornecedorJsonText(root, "cpf"));
        if (!cpfDigits.isEmpty()) {
            c.cnpj.setText(formatCpfUi(cpfDigits));
        }
        String nome = fornecedorJsonText(root, "nome");
        if (!nome.isEmpty()) {
            c.razao.setText(nome);
        }
    }

    private static void aplicarJsonViaCepFornecedor(JsonNode root, FornecedorCampos c) {
        c.endereco.setText(fornecedorJsonText(root, "logradouro"));
        c.bairro.setText(fornecedorJsonText(root, "bairro"));
        c.complemento.setText(fornecedorJsonText(root, "complemento"));
        c.cidade.setText(fornecedorJsonText(root, "localidade"));
        c.estado.setText(fornecedorJsonText(root, "uf"));
        String cepDigits = digitsOnlyFornecedor(fornecedorJsonText(root, "cep"));
        if (!cepDigits.isEmpty()) {
            c.cep.setText(formatCepUi(cepDigits));
        }
    }

    private void consultarDocumentoFornecedor(FornecedorCampos c, Window parent) {
        final Window win = parent != null ? parent : frame;
        String tipo = Objects.toString(c.tipoDocumento.getSelectedItem(), "CNPJ");
        String doc = digitsOnlyFornecedor(c.cnpj.getText());
        if ("CNPJ".equals(tipo)) {
            if (doc.length() != 14) {
                JOptionPane.showMessageDialog(win, "Informe os 14 digitos do CNPJ.",
                        "Consultar CNPJ", JOptionPane.WARNING_MESSAGE);
                return;
            }
            consultarCnpjBrasilApi(c, doc, win);
        } else {
            if (doc.length() != 11) {
                JOptionPane.showMessageDialog(win, "Informe os 11 digitos do CPF.",
                        "Consultar CPF", JOptionPane.WARNING_MESSAGE);
                return;
            }
            consultarCpfBrasilApi(c, doc, win);
        }
    }

    private void consultarCnpjBrasilApi(FornecedorCampos c, String cnpj14, Window parent) {
        final Window win = parent != null ? parent : frame;
        win.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        SwingWorker<JsonNode, Void> worker = new SwingWorker<>() {
            private String errMsg;

            @Override
            protected JsonNode doInBackground() {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("https://brasilapi.com.br/api/cnpj/v1/" + cnpj14))
                            .timeout(Duration.ofSeconds(18))
                            .GET()
                            .build();
                    HttpResponse<String> resp = FORNECEDOR_LOOKUP_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() != 200) {
                        errMsg = "Nao foi possivel consultar o CNPJ (HTTP " + resp.statusCode() + ").";
                        return null;
                    }
                    return FORNECEDOR_JSON.readTree(resp.body());
                } catch (Exception e) {
                    errMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                    return null;
                }
            }

            @Override
            protected void done() {
                win.setCursor(Cursor.getDefaultCursor());
                try {
                    if (errMsg != null) {
                        JOptionPane.showMessageDialog(win, errMsg, "Consultar CNPJ", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    JsonNode root = get();
                    if (root == null || !root.has("razao_social")) {
                        JOptionPane.showMessageDialog(win, "Resposta invalida da API de CNPJ.",
                                "Consultar CNPJ", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    aplicarJsonCnpjNasTelasFornecedor(root, c);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(win, ex.getMessage(), "Consultar CNPJ", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private void consultarCpfBrasilApi(FornecedorCampos c, String cpf11, Window parent) {
        final Window win = parent != null ? parent : frame;
        win.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        SwingWorker<JsonNode, Void> worker = new SwingWorker<>() {
            private String errMsg;

            @Override
            protected JsonNode doInBackground() {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("https://brasilapi.com.br/api/cpf/v1/" + cpf11))
                            .timeout(Duration.ofSeconds(18))
                            .GET()
                            .build();
                    HttpResponse<String> resp = FORNECEDOR_LOOKUP_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() != 200) {
                        errMsg = "Nao foi possivel consultar o CPF (HTTP " + resp.statusCode() + ").";
                        return null;
                    }
                    return FORNECEDOR_JSON.readTree(resp.body());
                } catch (Exception e) {
                    errMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                    return null;
                }
            }

            @Override
            protected void done() {
                win.setCursor(Cursor.getDefaultCursor());
                try {
                    if (errMsg != null) {
                        JOptionPane.showMessageDialog(win, errMsg, "Consultar CPF", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    JsonNode root = get();
                    if (root == null) {
                        JOptionPane.showMessageDialog(win, "Resposta vazia da API de CPF.",
                                "Consultar CPF", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    String msg = fornecedorJsonText(root, "message");
                    if (!msg.isEmpty() && !root.has("nome")) {
                        JOptionPane.showMessageDialog(win, msg, "Consultar CPF", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    aplicarJsonCpfNasTelasFornecedor(root, c);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(win, ex.getMessage(), "Consultar CPF", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private void consultarCepFornecedor(FornecedorCampos c, Window parent) {
        final Window win = parent != null ? parent : frame;
        final String cep8 = digitsOnlyFornecedor(c.cep.getText());
        if (cep8.length() != 8) {
            JOptionPane.showMessageDialog(win, "Informe o CEP com 8 digitos.",
                    "Consultar CEP", JOptionPane.WARNING_MESSAGE);
            return;
        }
        win.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        SwingWorker<JsonNode, Void> worker = new SwingWorker<>() {
            private String errMsg;

            @Override
            protected JsonNode doInBackground() {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("https://viacep.com.br/ws/" + cep8 + "/json/"))
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build();
                    HttpResponse<String> resp = FORNECEDOR_LOOKUP_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() != 200) {
                        errMsg = "Nao foi possivel consultar o CEP (HTTP " + resp.statusCode() + ").";
                        return null;
                    }
                    return FORNECEDOR_JSON.readTree(resp.body());
                } catch (Exception e) {
                    errMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                    return null;
                }
            }

            @Override
            protected void done() {
                win.setCursor(Cursor.getDefaultCursor());
                try {
                    if (errMsg != null) {
                        JOptionPane.showMessageDialog(win, errMsg, "Consultar CEP", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    JsonNode root = get();
                    if (root == null) {
                        JOptionPane.showMessageDialog(win, "Resposta vazia do ViaCEP.",
                                "Consultar CEP", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    JsonNode erro = root.get("erro");
                    boolean cepInvalido = erro != null && !erro.isNull()
                            && (erro.asBoolean(false) || "true".equalsIgnoreCase(erro.asText("").trim()));
                    if (cepInvalido) {
                        JOptionPane.showMessageDialog(win, "CEP nao encontrado.",
                                "Consultar CEP", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    aplicarJsonViaCepFornecedor(root, c);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(win, ex.getMessage(), "Consultar CEP", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private JScrollPane wrapFormularioFornecedorEmScroll(JPanel form) {
        JScrollPane sp = new JScrollPane(form);
        sp.setBorder(null);
        sp.getViewport().setBackground(PANEL_BG);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(20);
        return sp;
    }

    private JPanel fornecedoresPanel() {
        JPanel panel = page();
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        FornecedorCampos campos = new FornecedorCampos();
        montarCamposFornecedorNoFormulario(form, campos);
        JButton salvar = button("Gravar fornecedor");
        salvar.addActionListener(e -> {
            try {
                requireInventoryAccess();
                BusinessRules.requireNotBlank(campos.razao.getText(), "Razão social");
                String cnpjVal = campos.cnpj.getText().trim();
                String docTipo = Objects.toString(campos.tipoDocumento.getSelectedItem(), "CNPJ");
                int ativoVal = campos.ativo.isSelected() ? 1 : 0;
                update("""
                    insert into fornecedores (razao_social, nome_fantasia, documento_tipo, cnpj, telefone, celular, email, endereco, numero, bairro, complemento, contato,
                        cep, cidade, estado, inscricao_estadual, observacoes, ativo)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict(cnpj) do update set
                        razao_social=excluded.razao_social,
                        nome_fantasia=excluded.nome_fantasia,
                        documento_tipo=excluded.documento_tipo,
                        telefone=excluded.telefone,
                        celular=excluded.celular,
                        email=excluded.email,
                        endereco=excluded.endereco,
                        numero=excluded.numero,
                        bairro=excluded.bairro,
                        complemento=excluded.complemento,
                        contato=excluded.contato,
                        cep=excluded.cep,
                        cidade=excluded.cidade,
                        estado=excluded.estado,
                        inscricao_estadual=excluded.inscricao_estadual,
                        observacoes=excluded.observacoes,
                        ativo=excluded.ativo
                    """,
                        campos.razao.getText(), campos.fantasia.getText(),
                        docTipo,
                        cnpjVal.isEmpty() ? null : cnpjVal,
                        campos.telefone.getText(), campos.celular.getText(), campos.email.getText(),
                        campos.endereco.getText(),
                        nullIfBlank(campos.numero.getText()),
                        nullIfBlank(campos.bairro.getText()),
                        nullIfBlank(campos.complemento.getText()),
                        campos.contato.getText(),
                        nullIfBlank(campos.cep.getText()), nullIfBlank(campos.cidade.getText()),
                        nullIfBlank(campos.estado.getText()), nullIfBlank(campos.inscricaoEstadual.getText()),
                        nullIfBlank(campos.observacoes.getText()),
                        ativoVal);
                refreshEstoqueViews();
            } catch (Exception ex) { error(ex); }
        });
        final String sqlFornecedoresGrid = """
                select f.id as ID, f.razao_social as "Razão", f.nome_fantasia as Fantasia,
                       coalesce(f.documento_tipo, 'CNPJ') as Doc, f.cnpj as CPF_CNPJ,
                       coalesce(f.inscricao_estadual, '') as IE,
                       f.telefone as Telefone, coalesce(f.celular, '') as Celular, f.email as Email,
                       coalesce(f.cidade, '') as Cidade, coalesce(f.estado, '') as UF,
                       coalesce(f.cep, '') as CEP, f.contato as Contato,
                       case when coalesce(f.ativo, 1) = 0 then 'Nao' else 'Sim' end as Ativo
                from fornecedores f
                order by f.razao_social
                """;
        JTable tabelaFornecedores = table(sqlFornecedoresGrid);
        esconderColunaIdTabelaFornecedores(tabelaFornecedores);
        JButton editar = button("Editar fornecedor");
        editar.setToolTipText("Selecione uma linha na tabela abaixo e clique para alterar os dados do fornecedor.");
        editar.addActionListener(e -> editarFornecedorSelecionado(tabelaFornecedores, sqlFornecedoresGrid));
        JPanel botoes = new JPanel(new GridLayout(1, 2, 8, 0));
        botoes.setOpaque(false);
        botoes.add(salvar);
        botoes.add(editar);
        JPanel cadastroWrap = new JPanel(new BorderLayout(0, 10));
        cadastroWrap.setOpaque(false);
        JScrollPane formScroll = wrapFormularioFornecedorEmScroll(form);
        formScroll.setPreferredSize(new Dimension(0, scale(compactMode() ? 440 : 540)));
        cadastroWrap.add(formScroll, BorderLayout.CENTER);
        cadastroWrap.add(botoes, BorderLayout.SOUTH);
        panel.add(section("Cadastrar Fornecedor", cadastroWrap));
        panel.add(Box.createVerticalStrut(12));
        panel.add(section("Fornecedores Cadastrados", tabelaFornecedores));
        return panel;
    }

    private static void esconderColunaIdTabelaFornecedores(JTable t) {
        if (t.getColumnModel().getColumnCount() == 0) {
            return;
        }
        TableColumn col = t.getColumnModel().getColumn(0);
        col.setMinWidth(0);
        col.setMaxWidth(0);
        col.setPreferredWidth(0);
        col.setResizable(false);
    }

    private void editarFornecedorSelecionado(JTable tabela, String sqlLista) {
        try {
            requireInventoryAccess();
        } catch (Exception ex) {
            error(ex);
            return;
        }
        int viewRow = tabela.getSelectedRow();
        if (viewRow < 0) {
            JOptionPane.showMessageDialog(frame, "Selecione um fornecedor na tabela \"Fornecedores Cadastrados\".",
                    "Editar fornecedor", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int modelRow = tabela.convertRowIndexToModel(viewRow);
        Object idObj = tabela.getModel().getValueAt(modelRow, 0);
        if (idObj == null) {
            JOptionPane.showMessageDialog(frame, "Nao foi possivel identificar o fornecedor selecionado.",
                    "Editar fornecedor", JOptionPane.WARNING_MESSAGE);
            return;
        }
        long fornecedorId = ((Number) idObj).longValue();
        abrirDialogEditarFornecedor(fornecedorId, tabela, sqlLista);
    }

    private void abrirDialogEditarFornecedor(long fornecedorId, JTable tabelaLista, String sqlLista) {
        Map<String, Object> row;
        try {
            row = one("""
                    select id, razao_social, nome_fantasia, documento_tipo, cnpj, telefone, celular, email, endereco, numero, bairro, complemento, contato,
                           cep, cidade, estado, inscricao_estadual, observacoes, ativo
                    from fornecedores where id = ?
                    """, fornecedorId);
        } catch (Exception ex) {
            error(ex);
            return;
        }
        if (row == null) {
            JOptionPane.showMessageDialog(frame, "Fornecedor nao encontrado.", "Editar fornecedor", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JDialog dialog = new JDialog(frame, "Editar fornecedor", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.getContentPane().setBackground(PANEL_BG);
        dialog.setSize(scale(920), scale(720));
        dialog.setMinimumSize(new Dimension(scale(680), scale(560)));
        dialog.setLocationRelativeTo(frame);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        FornecedorCampos campos = new FornecedorCampos();
        campos.preencherDeLinha(row);
        montarCamposFornecedorNoFormulario(form, campos);

        JPanel sul = new JPanel(new GridLayout(1, 2, 8, 0));
        sul.setOpaque(false);
        JButton gravar = button("Gravar");
        JButton fechar = button("Fechar");
        gravar.addActionListener(e -> {
            try {
                requireInventoryAccess();
                BusinessRules.requireNotBlank(campos.razao.getText(), "Razão social");
                String cnpjVal = campos.cnpj.getText().trim();
                String docTipo = Objects.toString(campos.tipoDocumento.getSelectedItem(), "CNPJ");
                int ativoVal = campos.ativo.isSelected() ? 1 : 0;
                if (!cnpjVal.isEmpty()) {
                    Map<String, Object> outro = one("""
                            select id from fornecedores where cnpj = ? and id <> ?
                            """, cnpjVal, fornecedorId);
                    if (outro != null) {
                        JOptionPane.showMessageDialog(dialog, "Ja existe outro fornecedor com este CPF/CNPJ.",
                                "Editar fornecedor", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                }
                update("""
                        update fornecedores set razao_social=?, nome_fantasia=?, documento_tipo=?, cnpj=?, telefone=?, celular=?, email=?, endereco=?,
                            numero=?, bairro=?, complemento=?, contato=?,
                            cep=?, cidade=?, estado=?, inscricao_estadual=?, observacoes=?, ativo=?
                        where id=?
                        """,
                        campos.razao.getText(), campos.fantasia.getText(), docTipo, cnpjVal.isEmpty() ? null : cnpjVal,
                        campos.telefone.getText(), campos.celular.getText(), campos.email.getText(), campos.endereco.getText(),
                        nullIfBlank(campos.numero.getText()),
                        nullIfBlank(campos.bairro.getText()),
                        nullIfBlank(campos.complemento.getText()),
                        campos.contato.getText(),
                        nullIfBlank(campos.cep.getText()), nullIfBlank(campos.cidade.getText()),
                        nullIfBlank(campos.estado.getText()), nullIfBlank(campos.inscricaoEstadual.getText()),
                        nullIfBlank(campos.observacoes.getText()),
                        ativoVal,
                        fornecedorId);
                audit("FORNECEDOR_ALTERADO", "ID " + fornecedorId + " | " + campos.razao.getText());
                reloadTableSql(tabelaLista, sqlLista);
                esconderColunaIdTabelaFornecedores(tabelaLista);
                dialog.dispose();
            } catch (Exception ex) {
                error(ex);
            }
        });
        fechar.addActionListener(e -> dialog.dispose());
        sul.add(gravar);
        sul.add(fechar);

        JPanel centro = new JPanel(new BorderLayout(0, 10));
        centro.setOpaque(false);
        centro.setBorder(new EmptyBorder(8, 12, 4, 12));
        JLabel aviso = new JLabel("Altere os campos e clique em Gravar.");
        aviso.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));
        aviso.setForeground(TEXT_MUTED);
        centro.add(aviso, BorderLayout.NORTH);
        JScrollPane formScroll = wrapFormularioFornecedorEmScroll(form);
        formScroll.setPreferredSize(new Dimension(0, scale(560)));
        centro.add(formScroll, BorderLayout.CENTER);

        dialog.add(centro, BorderLayout.CENTER);
        dialog.add(sul, BorderLayout.SOUTH);
        dialog.setVisible(true);
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
        JButton importar = button("Selecionar arquivos");
        importar.addActionListener(e -> importarXml());
        // Click em qualquer lugar da drop zone abre o diálogo de seleção (mesmo comportamento do botao).
        dropZone.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { importar.doClick(); }
        });

        JPanel actions = new JPanel(new GridLayout(1, 2, 8, 8));
        actions.setOpaque(false);
        actions.add(abrirPasta);
        actions.add(importar);

        // Painel lateral: aproveita o espaco vazio ao lado da drop zone com
        // dicas rapidas de "como funciona" + status do importador (pasta
        // padrao, total de NF-e ja importadas e ultima importacao).
        JPanel sidePanel = buildXmlSidePanel(inbox);

        // Layout horizontal da area superior do card: drop zone a esquerda
        // (estica), painel de dicas/status a direita com largura fixa.
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
        panel.add(section("NF-e na fila (por fornecedor)", buildXmlNfePendentesPanel()));
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
                + "<b>1.</b> Salve os <b>.xml</b> da NF-e na pasta padrao (ou use <b>Selecionar arquivos</b>).<br>"
                + "<b>2.</b> Os XML entram na <b>fila</b> abaixo, agrupados por fornecedor.<br>"
                + "<b>3.</b> Revise e clique em <b>Dar baixa no estoque</b> para dar entrada nos produtos.<br>"
                + "<b>4.</b> Depois da baixa, a nota some da lista; voce pode importar o mesmo XML de novo se precisar."
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

        int naFila = safeInt("select count(*) from notas_fiscais where status='PENDENTE'");
        JLabel totalLbl = new JLabel("<html><b>XML na fila (pendente):</b> " + naFila + "</html>");
        totalLbl.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
        totalLbl.setForeground(TEXT_DARK);
        totalLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(totalLbl);
        side.add(Box.createVerticalStrut(4));

        String ultima = "Nenhuma";
        try {
            Map<String, Object> row = one(
                    "select numero_nf, importado_em from notas_fiscais where status='PENDENTE' order by id desc limit 1");
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

    /**
     * Lista NF-e com status PENDENTE, ordenada por fornecedor; a coluna
     * "Fornecedor" destaca visualmente o inicio de cada grupo. Acoes de
     * baixa no estoque e exclusao atuam nas linhas selecionadas.
     */
    private JPanel buildXmlNfePendentesPanel() {
        JPanel wrap = new JPanel(new BorderLayout(0, 8));
        wrap.setOpaque(false);
        try {
            List<Map<String, Object>> data = rows("""
                    select nf.id as id,
                           coalesce(nullif(trim(f.nome_fantasia), ''), nullif(trim(f.razao_social), ''), '(Fornecedor)') as fornecedor,
                           coalesce(nf.numero_nf, '') as numero_nf,
                           coalesce(nf.chave_acesso, '') as chave,
                           coalesce(nf.data, '') as data,
                           nf.total as total,
                           nf.importado_em as importado_em,
                           coalesce(nf.xml_path, '') as xml_path
                      from notas_fiscais nf
                      left join fornecedores f on f.id = nf.fornecedor_id
                     where nf.status = 'PENDENTE'
                     order by fornecedor collate nocase, nf.id desc
                    """);
            if (data.isEmpty()) {
                JLabel empty = new JLabel("<html><i>Nenhum XML pendente. Use <b>Selecionar arquivos</b> "
                        + "para importar; as notas aparecem aqui ate a baixa no estoque.</i></html>");
                empty.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));
                empty.setForeground(TEXT_MUTED);
                wrap.add(empty, BorderLayout.CENTER);
                return wrap;
            }
            Vector<String> cols = new Vector<>(List.of(
                    "Fornecedor", "NF", "Chave de acesso", "Emissao", "Total", "Importado em", "Arquivo XML", "_id"));
            Vector<Vector<Object>> lines = new Vector<>();
            for (Map<String, Object> row : data) {
                Vector<Object> line = new Vector<>();
                line.add(row.get("fornecedor"));
                line.add(row.get("numero_nf"));
                String ch = String.valueOf(row.get("chave"));
                if (ch.length() > 20) {
                    ch = ch.substring(0, 10) + "..." + ch.substring(ch.length() - 6);
                }
                line.add(ch);
                line.add(row.get("data"));
                line.add(row.get("total"));
                Object imp = row.get("importado_em");
                if (imp != null && imp.toString().matches("\\d{4}-\\d{2}-\\d{2}T.*")) {
                    line.add(LocalDateTime.parse(imp.toString()).format(BR_DATE_TIME));
                } else {
                    line.add(imp);
                }
                Path p = Path.of(String.valueOf(row.get("xml_path")));
                line.add(p.getFileName().toString());
                line.add(row.get("id"));
                lines.add(line);
            }
            DefaultTableModel model = new DefaultTableModel(lines, cols) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            JTable table = new JTable(model);
            table.setAutoCreateRowSorter(true);
            table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
            table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            styleTable(table);
            int idCol = model.getColumnCount() - 1;
            TableColumn idColumn = table.getColumnModel().getColumn(idCol);
            idColumn.setMinWidth(0);
            idColumn.setMaxWidth(0);
            idColumn.setWidth(0);
            idColumn.setPreferredWidth(0);

            table.getColumnModel().getColumn(0).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable t, Object val, boolean sel, boolean foc, int row, int col) {
                    JLabel lb = (JLabel) super.getTableCellRendererComponent(t, val, sel, foc, row, col);
                    int mr = t.convertRowIndexToModel(row);
                    boolean inicio = mr == 0;
                    if (!inicio) {
                        Object prev = t.getModel().getValueAt(mr - 1, 0);
                        inicio = !Objects.equals(String.valueOf(val), String.valueOf(prev));
                    }
                    Font base = t.getFont();
                    lb.setFont(inicio ? base.deriveFont(Font.BOLD) : base.deriveFont(Font.PLAIN));
                    lb.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(inicio ? 10 : 0, 0, 0, 0, new Color(0xC8, 0xE6, 0xC9)),
                            BorderFactory.createEmptyBorder(2, 0, 2, 0)));
                    return lb;
                }
            });

            JScrollPane scroll = new JScrollPane(table);
            scroll.setPreferredSize(new Dimension(200, compactMode() ? 200 : 280));
            wrap.add(scroll, BorderLayout.CENTER);

            JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            bar.setOpaque(false);
            JButton btnBaixa = button("Dar baixa no estoque");
            JButton btnExcluir = button("Excluir XML da lista");
            btnBaixa.setToolTipText("Entrada de estoque pelos itens do XML (produtos + historico de entradas), retira da fila e opcionalmente gera conta a pagar.");
            btnExcluir.setToolTipText("Remove o registro pendente (nao apaga o arquivo da pasta).");
            btnBaixa.addActionListener(e -> {
                try {
                    requireInventoryAccess();
                    int[] viewRows = table.getSelectedRows();
                    if (viewRows.length == 0) {
                        msg("Selecione uma ou mais linhas (XML / NF-e) na tabela.");
                        return;
                    }
                    Arrays.sort(viewRows);
                    int ok = 0;
                    List<String> erros = new ArrayList<>();
                    for (int vr : viewRows) {
                        int mr = table.convertRowIndexToModel(vr);
                        long id = ((Number) model.getValueAt(mr, idCol)).longValue();
                        try {
                            Map<String, Object> nfRow = one("""
                                    select nf.id, nf.fornecedor_id, nf.xml_path,
                                           coalesce(nf.total, 0) as total,
                                           coalesce(nf.numero_nf, '') as numero_nf,
                                           coalesce(nf.chave_acesso, '') as chave_nf
                                    from notas_fiscais nf
                                    where nf.id = ? and nf.status = 'PENDENTE'
                                    """, id);
                            if (nfRow == null || nfRow.isEmpty()) {
                                throw new AppException("Nota nao encontrada ou ja baixada.");
                            }
                            String pathStr = Objects.toString(nfRow.get("xml_path"), "");
                            if (pathStr.isBlank()) {
                                throw new AppException("XML sem caminho de arquivo.");
                            }
                            File xmlFile = new File(pathStr);
                            if (!xmlFile.exists()) {
                                throw new AppException("Arquivo XML nao encontrado: " + xmlFile.getName());
                            }
                            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile);
                            doc.getDocumentElement().normalize();
                            long fornecedorId = ((Number) nfRow.get("fornecedor_id")).longValue();
                            String numeroNf = Objects.toString(nfRow.get("numero_nf"), "");
                            String chave = Objects.toString(nfRow.get("chave_nf"), "");
                            BigDecimal totalNota = money(String.valueOf(nfRow.get("total")));
                            if (!abrirDialogConferenciaItensXmlNfe(id, doc, fornecedorId, numeroNf, chave, totalNota)) {
                                continue;
                            }
                            XmlNfeBoletoChoice esc = abrirDialogBoletoXmlNfe(id, doc, fornecedorId, numeroNf, chave, totalNota);
                            if (esc.pularEstaNota()) {
                                continue;
                            }
                            processarBaixaXmlNfe(id, doc, esc.lancamentos());
                            ok++;
                        } catch (Exception ex) {
                            erros.add("#" + id + ": " + friendlyMessage(ex));
                        }
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("Baixa concluida em ").append(ok).append(" nota(s).");
                    if (!erros.isEmpty()) {
                        sb.append("\n\nFalhas:\n").append(String.join("\n", erros));
                    }
                    msg(sb.toString());
                    refreshFrame();
                } catch (Exception ex) {
                    error(ex);
                }
            });
            btnExcluir.addActionListener(e -> {
                try {
                    requireInventoryAccess();
                    int[] viewRows = table.getSelectedRows();
                    if (viewRows.length == 0) {
                        msg("Selecione o(s) XML(s) na tabela para excluir da lista.");
                        return;
                    }
                    int op = JOptionPane.showConfirmDialog(frame,
                            "Remover " + viewRows.length + " XML(s) da lista pendente?\n"
                                    + "(O arquivo na pasta nao e apagado.)",
                            "Excluir XML",
                            JOptionPane.YES_NO_OPTION);
                    if (op != JOptionPane.YES_OPTION) {
                        return;
                    }
                    for (int vr : viewRows) {
                        int mr = table.convertRowIndexToModel(vr);
                        long id = ((Number) model.getValueAt(mr, idCol)).longValue();
                        update("delete from notas_fiscais where id=? and status='PENDENTE'", id);
                    }
                    audit("XML_NFE_EXCLUIDO", "Removidos " + viewRows.length + " registro(s) pendente(s)");
                    refreshFrame();
                } catch (Exception ex) {
                    error(ex);
                }
            });
            bar.add(btnBaixa);
            bar.add(btnExcluir);
            wrap.add(bar, BorderLayout.SOUTH);
        } catch (Exception e) {
            error(e);
        }
        return wrap;
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
        // A edicao de um cliente ja cadastrado e feita pelo botao Alterar.
        JLabel ajudaCadastro = new JLabel(
                "<html>\u2139 Use este formul\u00e1rio apenas para <b>cadastrar um cliente novo</b>." +
                "<br>Para alterar dados de um cliente j\u00e1 cadastrado, selecione na tabela " +
                "abaixo e clique em <b>Alterar</b>.</html>");
        ajudaCadastro.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
        ajudaCadastro.setForeground(new Color(0x5D, 0x40, 0x37));
        ajudaCadastro.setOpaque(true);
        ajudaCadastro.setBackground(new Color(0xFF, 0xF8, 0xE1));
        ajudaCadastro.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xFB, 0xC0, 0x2D), 1),
                new EmptyBorder(6, 10, 6, 10)));
        JButton salvar = button("\u2795  Novo");
        salvar.setToolTipText("Cadastra um cliente novo. Para editar, selecione na tabela e use Alterar.");
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
                                        + "\"Clientes\" e clique em \"Alterar\".");
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
        JTable tabelaClientes = new JTable();
        tabelaClientes.setAutoCreateRowSorter(true);
        tabelaClientes.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        styleTable(tabelaClientes);
        reloadConvenioClientesTable(tabelaClientes);
        JButton alterarCliente = button("\u270F\uFE0F  Alterar");
        alterarCliente.setToolTipText("Selecione um cliente na tabela \"Clientes\" e edite os dados.");
        alterarCliente.addActionListener(e -> alterarClienteFiado(tabelaClientes));
        // Botao "Baixa": busca o cliente; no dialogo o operador marca quais
        // lancamentos entram na baixa (F5-F8) e o valor; distribuicao FIFO
        // so entre os marcados.
        JButton darBaixa = button("\uD83D\uDCB5  Baixa");
        darBaixa.setToolTipText("Busca o cliente e abre a baixa no convênio: escolha quais lançamentos pagar (F5–F8) e o valor.");
        darBaixa.addActionListener(e -> darBaixaConvenio());
        JButton btnItensConvenioAberto = button("\uD83D\uDCCD  Itens");
        btnItensConvenioAberto.setToolTipText("Com um cliente selecionado na tabela \"Clientes\", mostra os produtos das compras no convênio ainda em aberto.");
        btnItensConvenioAberto.addActionListener(e -> mostrarItensConvenioEmAberto(tabelaClientes));
        JButton excluirCliente = styledButton("\uD83D\uDDD1  Excluir cliente selecionado", MARKET_RED);
        excluirCliente.addActionListener(e -> excluirClienteFiado(tabelaClientes));

        // Linha de acoes no card de cadastro (Novo, Alterar, Baixa, Itens).
        JPanel acoesCadastro = new JPanel(new GridLayout(1, 4, 6, 0));
        acoesCadastro.setOpaque(false);
        Font fonteBotoesConvenio = new Font("Segoe UI", Font.BOLD, fontSize(compactMode() ? 11 : 12));
        for (JButton b : new JButton[]{salvar, alterarCliente, darBaixa, btnItensConvenioAberto}) {
            b.setFont(fonteBotoesConvenio);
        }
        acoesCadastro.add(salvar);
        acoesCadastro.add(alterarCliente);
        acoesCadastro.add(darBaixa);
        acoesCadastro.add(btnItensConvenioAberto);

        JPanel cadastroBody = new JPanel(new BorderLayout(0, 10));
        cadastroBody.setOpaque(false);
        cadastroBody.add(ajudaCadastro, BorderLayout.NORTH);
        cadastroBody.add(form, BorderLayout.CENTER);
        cadastroBody.add(acoesCadastro, BorderLayout.SOUTH);
        panel.add(section("Cadastrar novo cliente", cadastroBody));
        panel.add(Box.createVerticalStrut(10));

        panel.add(section("Clientes", tabelaClientes));

        JPanel rodapeConvenio = new JPanel();
        rodapeConvenio.setLayout(new BoxLayout(rodapeConvenio, BoxLayout.Y_AXIS));
        rodapeConvenio.setOpaque(false);
        rodapeConvenio.setBorder(new EmptyBorder(8, 0, 0, 0));
        int altBtn = excluirCliente.getPreferredSize().height + scale(4);
        Dimension fullW = new Dimension(Integer.MAX_VALUE, altBtn);
        excluirCliente.setAlignmentX(Component.CENTER_ALIGNMENT);
        excluirCliente.setMaximumSize(fullW);
        rodapeConvenio.add(excluirCliente);
        panel.add(rodapeConvenio);
        // Conecta o refresher global: chamado pelo PDV apos cada venda
        // FIADO e por outros pontos que mexem em fiado.
        Runnable antesFiado = convenioRefresher;
        convenioRefresher = () -> SwingUtilities.invokeLater(() -> {
            antesFiado.run();
            reloadConvenioClientesTable(tabelaClientes);
        });
        return panel;
    }

    /** Atualiza blocos do Painel que dependem do banco em tempo real (convênio, estoque critico, validades). */
    private void refreshDashboardPainelWidgets() {
        if (dashboardConvenioAbertoTable != null) {
            reloadTableSql(dashboardConvenioAbertoTable, SQL_DASHBOARD_CONVENIO_ABERTO);
        }
        if (dashboardConvenioKpiCountLabel != null) {
            dashboardConvenioKpiCountLabel.setText(String.valueOf(safeInt(SQL_DASHBOARD_CONVENIO_KPI_COUNT)));
        }
        if (dashboardEstoqueCriticoTable != null) {
            reloadTableSql(dashboardEstoqueCriticoTable, SQL_DASHBOARD_ESTOQUE_CRITICO);
        }
        if (dashboardEstoqueBaixoKpiCountLabel != null) {
            dashboardEstoqueBaixoKpiCountLabel.setText(String.valueOf(safeInt(SQL_DASHBOARD_ESTOQUE_CRITICO_COUNT)));
        }
        if (dashboardValidades30Table != null) {
            reloadTableSql(dashboardValidades30Table, SQL_DASHBOARD_VALIDADES_30D);
        }
        if (dashboardValidades30KpiCountLabel != null) {
            dashboardValidades30KpiCountLabel.setText(String.valueOf(safeInt(SQL_DASHBOARD_VALIDADES_30D_COUNT)));
        }
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
                    line.add(formatTableSqlCell(value));
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

    /** Valor monetario vindo do SQLite (REAL/double ou texto) para celulas de tabela. */
    private BigDecimal moneyCellFromRow(Object v) {
        if (v == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (v instanceof BigDecimal b) {
            return b.setScale(2, RoundingMode.HALF_UP);
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        }
        return money(v.toString());
    }

    /** Recarrega a grade de clientes do convênio (CPF formatado, Limite/Divida em R$, divida em vermelho se positiva). */
    private void reloadConvenioClientesTable(JTable tabela) {
        try {
            List<Map<String, Object>> data = rows(SQL_CLIENTES_CONVENIO);
            Vector<String> cols = new Vector<>(List.of("ID", "Nome", "CPF", "Telefone", "Endereco", "Limite", "Divida"));
            Vector<Vector<Object>> lines = new Vector<>();
            for (Map<String, Object> row : data) {
                Vector<Object> line = new Vector<>();
                Object id = row.get("ID");
                if (id == null) {
                    id = row.get("id");
                }
                line.add(id instanceof Number ? ((Number) id).longValue() : id);
                Object nome = row.get("Nome");
                if (nome == null) {
                    nome = row.get("nome");
                }
                line.add(nome);
                Object cpfRaw = row.get("CPF");
                if (cpfRaw == null) {
                    cpfRaw = row.get("cpf");
                }
                String cpfDigits = Objects.toString(cpfRaw, "").replaceAll("\\D", "");
                line.add(cpfDigits.length() == 11 ? formatCpf(cpfDigits) : Objects.toString(cpfRaw, ""));
                Object tel = row.get("Telefone");
                if (tel == null) {
                    tel = row.get("telefone");
                }
                line.add(Objects.toString(tel, ""));
                Object end = row.get("Endereco");
                if (end == null) {
                    end = row.get("endereco");
                }
                line.add(Objects.toString(end, ""));
                Object lim = row.get("Limite");
                if (lim == null) {
                    lim = row.get("limite");
                }
                line.add(moneyCellFromRow(lim));
                Object div = row.get("Divida");
                if (div == null) {
                    div = row.get("divida");
                }
                line.add(moneyCellFromRow(div));
                lines.add(line);
            }
            DefaultTableModel novo = new DefaultTableModel(lines, cols) {
                @Override
                public boolean isCellEditable(int r, int c) {
                    return false;
                }
            };
            tabela.setModel(novo);
            aplicarRenderersTabelaClientesConvenio(tabela);
        } catch (Exception e) {
            appLog("WARN", "RELOAD_CONVENIO_CLIENTES", "Falha ao recarregar tabela convenio",
                    e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private void aplicarRenderersTabelaClientesConvenio(JTable tabela) {
        for (int i = 0; i < tabela.getColumnCount(); i++) {
            String name = tabela.getColumnName(i);
            if ("Divida".equals(name)) {
                tabela.getColumnModel().getColumn(i).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                            boolean hasFocus, int row, int column) {
                        JLabel lb = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                        BigDecimal d = value instanceof BigDecimal b ? b : moneyCellFromRow(value);
                        lb.setText(moneyText(d));
                        lb.setHorizontalAlignment(SwingConstants.RIGHT);
                        if (!isSelected) {
                            lb.setForeground(d.compareTo(BigDecimal.ZERO) > 0 ? MARKET_RED : TEXT_DARK);
                        } else {
                            lb.setForeground(table.getSelectionForeground());
                        }
                        return lb;
                    }
                });
            } else if ("Limite".equals(name)) {
                tabela.getColumnModel().getColumn(i).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                            boolean hasFocus, int row, int column) {
                        JLabel lb = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                        BigDecimal d = value instanceof BigDecimal b ? b : moneyCellFromRow(value);
                        lb.setText(moneyText(d));
                        lb.setHorizontalAlignment(SwingConstants.RIGHT);
                        return lb;
                    }
                });
            }
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
                refreshEstoqueViews();
            } catch (Exception ex) { error(ex); }
        });

        panel.add(section("Novo lancamento", formWithAction(form, salvar)));
        panel.add(Box.createVerticalStrut(12));

        JTable tabPendenciasFinanceiro = table("""
            select id as ID, tipo as Tipo, descricao as Descricao, coalesce(parceiro,'-') as Parceiro,
                   valor_total as Total, valor_baixado as Baixado, (valor_total-valor_baixado) as Aberto,
                   vencimento as Vencimento, status as Status
            from financeiro_lancamentos
            where status in ('ABERTO','PARCIAL')
            order by date(vencimento), id
            """);
        JScrollPane scrollPendFin = new JScrollPane(tabPendenciasFinanceiro);
        scrollPendFin.setBorder(BorderFactory.createLineBorder(BORDER_SOFT));
        scrollPendFin.getViewport().setBackground(PANEL_BG);
        JPanel barPendFin = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        barPendFin.setOpaque(false);
        JButton btnRegPagFin = button("Registrar pagamento / status");
        btnRegPagFin.setToolTipText("Selecione uma linha na tabela, depois informe se segue em aberto ou registre a baixa (valor e forma).");
        btnRegPagFin.addActionListener(e -> abrirDialogRegistrarPagamentoFinanceiro(tabPendenciasFinanceiro));
        barPendFin.add(btnRegPagFin);
        JPanel wrapPendFin = new JPanel(new BorderLayout(0, 8));
        wrapPendFin.setOpaque(false);
        wrapPendFin.add(scrollPendFin, BorderLayout.CENTER);
        wrapPendFin.add(barPendFin, BorderLayout.SOUTH);
        panel.add(section("Pendencias por vencimento", wrapPendFin));
        panel.add(Box.createVerticalStrut(12));
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
        JLabel tituloRel = new JLabel("Relatórios gerenciais");
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
        JButton btnAtualizarRel = button("Atualizar relatório");
        filtros.add(btnAtualizarRel);
        JButton btnRelEstoque = button("Relatório estoque");
        btnRelEstoque.setToolTipText("Mostra tudo que alterou ou mediu o estoque na data selecionada.");
        filtros.add(btnRelEstoque);
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

                linhaVendas.add(section("Ranking por quantidade (dia)",
                        table(productReportService.rankingByQuantity(dia, caixaFiltro, DesktopProductReportService.RANKING_LIMIT)), true));
                relatorioBody.add(linhaVendas);
                relatorioBody.add(Box.createVerticalStrut(compactMode() ? 6 : 8));

                JPanel linhaProdutos = new JPanel(new GridLayout(1, 2, compactMode() ? 8 : 10, compactMode() ? 8 : 10));
                linhaProdutos.setOpaque(false);
                linhaProdutos.add(section("Ranking por faturamento (dia)",
                        table(productReportService.rankingByRevenue(dia, caixaFiltro, DesktopProductReportService.RANKING_LIMIT)), true));
                linhaProdutos.add(section("Curva ABC por faturamento (dia)",
                        table(productReportService.abcByRevenue(dia, caixaFiltro)), true));
                relatorioBody.add(linhaProdutos);
                relatorioBody.add(Box.createVerticalStrut(compactMode() ? 6 : 8));

                String sqlSupSang = """
                        select co.id as ID,
                               c.numero as Caixa,
                               case co.tipo when 'SUPRIMENTO' then 'Suprimento' when 'SANGRIA' then 'Sangria' else co.tipo end as Tipo,
                               co.valor as Valor,
                               coalesce(co.motivo,'-') as Motivo,
                               u.nome as Operador,
                               co.timestamp as RegistradoEm
                        from caixa_operacoes co
                        join caixas c on c.id = co.caixa_id
                        join usuarios u on u.id = co.operador_id
                        where date(co.timestamp) = date(?)
                          and co.tipo in ('SUPRIMENTO', 'SANGRIA')
                        """ + (caixaFiltro == null ? "" : " and co.caixa_id=? ")
                        + " order by co.timestamp desc, co.id desc";
                relatorioBody.add(section("Suprimento e sangria (dia)", table(sqlSupSang,
                        caixaFiltro == null ? new Object[]{dia.toString()} : new Object[]{dia.toString(), caixaFiltro}), true));
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
                linhaOperacional.add(section("Devoluções do dia", table(sqlDev,
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
                linhaHistorico.add(section("Validades próximas por lote", table("""
                        select p.nome as Produto, coalesce(e.lote,'-') as Lote, e.validade as Validade, e.quantidade as Quantidade, e.documento as Documento
                        from entradas_estoque e join produtos p on p.id = e.produto_id
                        where e.validade is not null and date(e.validade) <= date('now','+30 day')
                        order by date(e.validade), p.nome
                        """), true));
                relatorioBody.add(linhaHistorico);
                relatorioBody.revalidate();
                relatorioBody.repaint();
            } catch (Exception ex) {
                error(ex);
            }
        };
        Runnable rebuildEstoque = () -> {
            try {
                LocalDate dia = ((java.util.Date) spData.getValue()).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                relatorioBody.removeAll();

                JPanel linhaResumoEstoque = new JPanel(new GridLayout(1, 2, compactMode() ? 8 : 10, compactMode() ? 8 : 10));
                linhaResumoEstoque.setOpaque(false);
                linhaResumoEstoque.add(section("Resumo das alteracoes no estoque (dia)", table("""
                        select m.tipo as Tipo,
                               count(*) as Lancamentos,
                               sum(m.quantidade) as Quantidade,
                               sum(abs(m.quantidade) * coalesce(p.preco_custo, 0)) as ValorCusto
                        from movimentacao_estoque m
                        join produtos p on p.id = m.produto_id
                        where date(m.timestamp) = date(?)
                        group by m.tipo
                        order by m.tipo
                        """, dia.toString()), true));
                linhaResumoEstoque.add(section("Inventario medido no dia", table("""
                        select ia.id as ID,
                               p.nome as Produto,
                               ia.saldo_sistema as Sistema,
                               ia.saldo_contado as Contado,
                               ia.diferenca as Diferenca,
                               ia.motivo as Motivo,
                               u.nome as Operador,
                               ia.criado_em as RegistradoEm
                        from inventario_ajustes ia
                        join produtos p on p.id = ia.produto_id
                        left join usuarios u on u.id = ia.operador_id
                        where date(ia.criado_em) = date(?)
                        order by ia.criado_em desc, ia.id desc
                        """, dia.toString()), true));
                relatorioBody.add(linhaResumoEstoque);
                relatorioBody.add(Box.createVerticalStrut(compactMode() ? 6 : 8));

                relatorioBody.add(section("Tudo que movimentou estoque no dia", table("""
                        select m.id as ID,
                               datetime(m.timestamp) as DataHora,
                               p.id as ProdutoID,
                               p.nome as Produto,
                               m.tipo as Tipo,
                               m.quantidade as Quantidade,
                               p.estoque_atual as EstoqueAtual,
                               p.estoque_minimo as EstoqueMinimo,
                               coalesce(u.nome, '-') as Operador,
                               coalesce(m.referencia_id, '-') as Referencia,
                               coalesce(m.observacao, '-') as Observacao
                        from movimentacao_estoque m
                        join produtos p on p.id = m.produto_id
                        left join usuarios u on u.id = m.operador_id
                        where date(m.timestamp) = date(?)
                        order by m.timestamp desc, m.id desc
                        """, dia.toString()), true));
                relatorioBody.add(Box.createVerticalStrut(compactMode() ? 6 : 8));

                relatorioBody.add(section("Produtos afetados e saldo atual", table("""
                        select p.id as ID,
                               p.nome as Produto,
                               p.estoque_atual as EstoqueAtual,
                               p.estoque_minimo as EstoqueMinimo,
                               p.preco_custo as Custo,
                               (p.estoque_atual * p.preco_custo) as ValorEmEstoque,
                               count(m.id) as MovimentacoesNoDia,
                               max(datetime(m.timestamp)) as UltimaAlteracao
                        from produtos p
                        join movimentacao_estoque m on m.produto_id = p.id
                        where date(m.timestamp) = date(?)
                        group by p.id
                        order by max(m.timestamp) desc, p.nome
                        """, dia.toString()), true));

                relatorioBody.revalidate();
                relatorioBody.repaint();
            } catch (Exception ex) {
                error(ex);
            }
        };
        btnAtualizarRel.addActionListener(e -> rebuild.run());
        btnRelEstoque.addActionListener(e -> rebuildEstoque.run());
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
        kpis.add(metricCardRelatorio("Ticket médio", moneyText(totTicket), MARKET_GREEN));
        kpis.add(metricCardRelatorio("Cartão (deb/cred)", moneyText(totCard), MARKET_ORANGE));
        kpis.add(metricCardRelatorio("Devoluções do dia", moneyText(totDev), MARKET_RED));
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
            refreshEstoqueViews();
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
            String imagemUrl = imagemUrlDoProduto(p);
            BigDecimal estoqueAtual = money(String.valueOf(p.get("estoque_atual")));

            int idxExistente = -1;
            for (int i = 0; i < cart.size(); i++) {
                if (cart.get(i).produtoId() == produtoId) {
                    idxExistente = i;
                    break;
                }
            }
            BigDecimal novaQtdCarrinho = idxExistente >= 0
                    ? cart.get(idxExistente).qtd().add(qtd)
                    : qtd;

            if (novaQtdCarrinho.compareTo(estoqueAtual) > 0) {
                if (!pdvEstoqueAutorizadoPorAdminIds.contains(produtoId)) {
                    if (!solicitarSenhaAdminParaExcederEstoqueAoIncluir(nomeProduto, estoqueAtual, novaQtdCarrinho)) {
                        return;
                    }
                    pdvEstoqueAutorizadoPorAdminIds.add(produtoId);
                    audit("PDV_ADD_ESTOQUE_ADMIN", user.login + " :: " + nomeProduto
                            + " pedido " + BR_NUMBER.format(novaQtdCarrinho)
                            + " | estoque " + BR_NUMBER.format(estoqueAtual));
                }
            }

            if (idxExistente >= 0) {
                CartItem existente = cart.get(idxExistente);
                String imgLinha = preferNonBlankImageUrl(existente.imagemUrl(), imagemUrl);
                cart.set(idxExistente, new CartItem(produtoId, nomeProduto, novaQtdCarrinho, preco,
                        existente.desconto(), imgLinha));
                refreshCart();
                return;
            }
            cart.add(new CartItem(produtoId, nomeProduto, qtd, preco, BigDecimal.ZERO, imagemUrl));
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
                    BR_NUMBER.format(money(String.valueOf(p.get("estoque_atual"))))
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
                    BR_NUMBER.format(money(String.valueOf(p.get("estoque_atual"))))
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

    private static ImageIcon pdvCartPlaceholder() {
        final int target = 28;
        ImageIcon cached = pdvCartPlaceholderIcon;
        if (cached != null && cached.getIconWidth() == target && cached.getIconHeight() == target) {
            return cached;
        }
        synchronized (DesktopApp.class) {
            cached = pdvCartPlaceholderIcon;
            if (cached != null && cached.getIconWidth() == target && cached.getIconHeight() == target) {
                return cached;
            }
            int s = target;
            BufferedImage bi = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = bi.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xF5, 0xF5, 0xF5));
            g.fillRoundRect(0, 0, s, s, 8, 8);
            g.setColor(new Color(0xD0, 0xD0, 0xD0));
            g.drawRoundRect(0, 0, s - 1, s - 1, 8, 8);
            g.dispose();
            cached = new ImageIcon(bi);
            pdvCartPlaceholderIcon = cached;
            return cached;
        }
    }

    private static String imagemUrlDoProduto(Map<String, Object> p) {
        if (p == null) {
            return null;
        }
        Object v = p.get("imagem_url");
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String preferNonBlankImageUrl(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private ImageIcon iconForCartLine(CartItem item) {
        return iconForProductImageUrl(item.imagemUrl());
    }

    private ImageIcon iconForProductImageUrl(String url) {
        if (url == null || url.isBlank()) {
            return pdvCartPlaceholder();
        }
        String key = url.trim();
        ImageIcon hit = pdvCartImageCache.get(key);
        if (hit != null) {
            return hit;
        }
        scheduleCartThumbDownload(key);
        return pdvCartPlaceholder();
    }

    private void scheduleCartThumbDownload(String url) {
        if (!pdvCartImageLoading.add(url)) {
            return;
        }
        SwingWorker<ImageIcon, Void> w = new SwingWorker<>() {
            @Override
            protected ImageIcon doInBackground() {
                try {
                    return downloadPdvCartThumb(url);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void done() {
                pdvCartImageLoading.remove(url);
                if (isCancelled()) {
                    return;
                }
                try {
                    ImageIcon ic = get();
                    if (ic == null) {
                        return;
                    }
                    if (pdvCartImageCache.size() > 120) {
                        pdvCartImageCache.clear();
                    }
                    pdvCartImageCache.put(url, ic);
                    applyCartThumbToRows(url, ic);
                } catch (Exception ignored) {
                    // cancelado ou sem resultado
                }
            }
        };
        w.execute();
    }

    private void applyCartThumbToRows(String url, ImageIcon icon) {
        SwingUtilities.invokeLater(() -> {
            if (cartModel == null) {
                return;
            }
            for (int i = 0; i < cart.size() && i < cartModel.getRowCount(); i++) {
                if (url.equals(cart.get(i).imagemUrl())) {
                    cartModel.setValueAt(icon, i, 0);
                }
            }
        });
    }

    private ImageIcon downloadPdvCartThumb(String urlString) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(urlString))
                .timeout(Duration.ofSeconds(22))
                .header("User-Agent", "MercadoDoTunicoPDV/1.0 (desktop; carrinho)")
                .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                .GET()
                .build();
        HttpResponse<byte[]> resp = PDV_CART_IMAGE_HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 400) {
            return null;
        }
        byte[] body = resp.body();
        if (body == null || body.length == 0) {
            return null;
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(body)) {
            BufferedImage src = ImageIO.read(in);
            if (src == null) {
                return null;
            }
            int max = compactMode() ? 24 : 30;
            double scale = Math.min((double) max / src.getWidth(), (double) max / src.getHeight());
            int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
            Image scaled = src.getScaledInstance(w, h, Image.SCALE_SMOOTH);
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = out.createGraphics();
            g2.drawImage(scaled, 0, 0, null);
            g2.dispose();
            return new ImageIcon(out);
        }
    }

    private void refreshCart() {
        if (cartModel == null) {
            return;
        }
        cartModel.setRowCount(0);
        BigDecimal liquidos = BigDecimal.ZERO;
        for (CartItem item : cart) {
            BigDecimal liquido = item.valorLiquidoLinha();
            liquidos = liquidos.add(liquido);
            BigDecimal estDisp = safeMoney(
                    "select coalesce(estoque_atual,0) as x from produtos where id=?", item.produtoId());
            cartModel.addRow(new Object[]{
                    iconForCartLine(item),
                    item.nome(), item.qtd(), moneyText(item.preco()),
                    moneyText(liquido),
                    BR_NUMBER.format(estDisp)
            });
        }
        totalLabel.setText(moneyText(liquidos.max(BigDecimal.ZERO)));
        paymentFeedbackUpdater.run();
        prunePdvEstoqueAutorizacao();
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
            cart.set(modelIndex, new CartItem(atual.produtoId(), atual.nome(), atual.qtd(), atual.preco(), novoDesc,
                    atual.imagemUrl()));
            refreshCart();
        } catch (Exception ex) {
            error(ex);
        }
    }

    /**
     * Monta uma linha por produto no carrinho cuja quantidade excede o estoque atual.
     */
    private List<String> resumoEstoqueInsuficienteNoCarrinho() throws Exception {
        List<String> linhas = new ArrayList<>();
        for (CartItem item : cart) {
            Map<String, Object> p = one("select coalesce(estoque_atual,0) as estoque_atual, nome from produtos where id=?", item.produtoId());
            if (p == null) {
                continue;
            }
            BigDecimal est = money(p.get("estoque_atual").toString());
            if (item.qtd().compareTo(est) > 0) {
                linhas.add(p.get("nome") + ": pedido " + BR_NUMBER.format(item.qtd())
                        + " | em estoque " + BR_NUMBER.format(est));
            }
        }
        return linhas;
    }

    private Set<Long> produtosIdsEstoqueInsuficienteNoCarrinho() throws Exception {
        Set<Long> ids = new HashSet<>();
        for (CartItem item : cart) {
            Map<String, Object> p = one("select coalesce(estoque_atual,0) as estoque_atual from produtos where id=?", item.produtoId());
            if (p == null) {
                continue;
            }
            BigDecimal est = money(p.get("estoque_atual").toString());
            if (item.qtd().compareTo(est) > 0) {
                ids.add(item.produtoId());
            }
        }
        return ids;
    }

    private void prunePdvEstoqueAutorizacao() {
        if (pdvEstoqueAutorizadoPorAdminIds.isEmpty()) {
            return;
        }
        Set<Long> ainda = new HashSet<>();
        for (CartItem it : cart) {
            ainda.add(it.produtoId());
        }
        pdvEstoqueAutorizadoPorAdminIds.removeIf(id -> !ainda.contains(id));
    }

    /**
     * Autoriza operacoes sensiveis com a senha real de login de qualquer
     * usuario ADMIN ou GERENTE ativo no banco.
     */
    private boolean adminSenhaConfere(char[] senhaDigitada) throws Exception {
        if (senhaDigitada == null || senhaDigitada.length == 0) {
            return false;
        }
        String plain = new String(senhaDigitada).trim();
        java.util.Arrays.fill(senhaDigitada, '\0');
        if (plain.isEmpty()) {
            return false;
        }
        for (Map<String, Object> row : rows(
                "select senha_hash from usuarios where role in ('ADMIN','GERENTE') and ativo=1 and senha_hash is not null and trim(senha_hash)<>''")) {
            if (encoder.matches(plain, row.get("senha_hash").toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean solicitarSenhaAdminParaExcederEstoqueAoIncluir(String nome, BigDecimal est, BigDecimal pedido) throws Exception {
        JPanel p = new JPanel(new GridLayout(0, 1, 8, 8));
        p.add(new JLabel("<html>Estoque disponivel: <b>" + BR_NUMBER.format(est)
                + "</b><br>Quantidade no carrinho (com este lancamento): <b>" + BR_NUMBER.format(pedido) + "</b></html>"));
        p.add(new JLabel("Produto: " + nome));
        p.add(new JLabel("<html>Digite a <b>senha de autorizacao</b> do estabelecimento "
                + "ou a senha de login do <b>Admin</b> / <b>Gerente</b>:</html>"));
        JPasswordField pf = new JPasswordField(18);
        pf.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(14)));
        p.add(pf);
        int op = JOptionPane.showConfirmDialog(frame, p, "Estoque insuficiente",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (op != JOptionPane.OK_OPTION) {
            return false;
        }
        if (!adminSenhaConfere(pf.getPassword())) {
            msg("Senha de administrador invalida. Ajuste a quantidade ou aguarde reposicao.");
            return false;
        }
        return true;
    }

    private boolean solicitarSenhaAdminParaAutorizarEstoqueInsuficiente(List<String> linhas) throws Exception {
        JPanel form = new JPanel(new BorderLayout(8, 8));
        JTextArea ta = new JTextArea(String.join("\n", linhas));
        ta.setEditable(false);
        ta.setOpaque(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setColumns(42);
        ta.setRows(Math.min(8, linhas.size() + 1));
        JLabel msg = new JLabel("<html>Nao e possivel concluir a venda sem autorizacao.<br>"
                + "Itens com quantidade maior que o estoque atual:</html>");
        JPasswordField pf = new JPasswordField(18);
        pf.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(14)));
        JPanel south = new JPanel(new GridLayout(0, 1, 6, 6));
        south.add(msg);
        south.add(new JLabel("<html>Senha de <b>autorizacao</b> ou login do <b>Admin</b> / <b>Gerente</b>:</html>"));
        south.add(pf);
        form.add(new JScrollPane(ta), BorderLayout.CENTER);
        form.add(south, BorderLayout.SOUTH);
        int op = JOptionPane.showConfirmDialog(frame, form, "Autorizar venda com estoque insuficiente",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (op != JOptionPane.OK_OPTION) {
            return false;
        }
        if (!adminSenhaConfere(pf.getPassword())) {
            msg("Senha de administrador invalida. Ajuste as quantidades no carrinho ou aguarde reposicao.");
            return false;
        }
        return true;
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
            List<String> estoqueProblemas = resumoEstoqueInsuficienteNoCarrinho();
            Set<Long> idsProb = produtosIdsEstoqueInsuficienteNoCarrinho();
            final boolean estoqueOverrideGerente;
            if (!estoqueProblemas.isEmpty()) {
                boolean allPreAutorizados = idsProb.stream().allMatch(pdvEstoqueAutorizadoPorAdminIds::contains);
                if (!allPreAutorizados) {
                    if (!solicitarSenhaAdminParaAutorizarEstoqueInsuficiente(estoqueProblemas)) {
                        return;
                    }
                    pdvEstoqueAutorizadoPorAdminIds.addAll(idsProb);
                }
                estoqueOverrideGerente = true;
                audit("VENDA_ESTOQUE_OVERRIDE", user.login + " :: " + String.join(" | ", estoqueProblemas));
            } else {
                estoqueOverrideGerente = false;
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
                    Map<String, Object> p = one("select nome, estoque_atual, preco_custo from produtos where id=?", item.produtoId());
                    BigDecimal estoqueAtual = money(p.get("estoque_atual").toString());
                    if (!estoqueOverrideGerente) {
                        BusinessRules.ensureStockAvailable(estoqueAtual, item.qtd(), p.get("nome").toString());
                    }
                    update("insert into venda_itens (venda_id, produto_id, quantidade, preco_unitario, custo_unitario) values (?, ?, ?, ?, ?)",
                            id, item.produtoId(), item.qtd(), item.preco(), p.get("preco_custo"));
                    int changed;
                    if (estoqueOverrideGerente) {
                        changed = updateCount("update produtos set estoque_atual=estoque_atual-? where id=?",
                                item.qtd(), item.produtoId());
                    } else {
                        changed = updateCount("update produtos set estoque_atual=estoque_atual-? where id=? and estoque_atual >= ?",
                                item.qtd(), item.produtoId(), item.qtd());
                    }
                    if (changed == 0) {
                        throw new AppException("Nao foi possivel baixar estoque para " + p.get("nome")
                                + ". Tente novamente ou verifique se outro caixa vendeu o mesmo item.");
                    }
                    update("insert into movimentacao_estoque (produto_id, tipo, quantidade, referencia_id, operador_id, timestamp, observacao) values (?, 'VENDA', ?, ?, ?, ?, ?)",
                            item.produtoId(), item.qtd(), id, user.id, LocalDateTime.now().toString(), "Venda #" + id);
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
            // A grade "Produtos" na aba Estoque e montada uma vez no buildFrame;
            // sem recarregar, o operador ve estoque antigo mesmo com UPDATE no banco.
            refreshEstoqueProdutosTableFromDb();
            // Atualiza Fundo (dinheiro), Cartao e PIX no topo do PDV apos cada
            // venda. Assim o operador ve em tempo real o saldo do caixa.
            pdvHeaderRefresher.run();
            SwingUtilities.invokeLater(this::refreshDashboardPainelWidgets);
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

    /** Entrada do dialogo de suprimento/sangria (valor + motivo). */
    private record ValorMotivoCaixaOperacao(BigDecimal valor, String motivo) {}

    /**
     * Dialogo unico para suprimento/sangria: valor com {@link #bindMoneyMaskLive}
     * (formato 20,00 / 1.000,00 enquanto digita) e motivo no mesmo passo.
     *
     * @return {@code null} se cancelar ou fechar sem confirmar.
     */
    private ValorMotivoCaixaOperacao dialogValorEMotivoSuprimentoSangria(String tituloDialog) {
        final ValorMotivoCaixaOperacao[] out = new ValorMotivoCaixaOperacao[1];
        JDialog dialog = new JDialog(frame, tituloDialog, true);
        dialog.setLayout(new BorderLayout(0, 0));
        dialog.getContentPane().setBackground(PANEL_BG);

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.setBorder(new EmptyBorder(14, 16, 8, 16));
        JLabel info = new JLabel("<html>Digite o valor em centavos automaticos "
                + "(ex.: <b>2000</b> vira <b>20,00</b>; <b>100000</b> vira <b>1.000,00</b>). "
                + "Depois informe o motivo.</html>");
        info.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));
        info.setForeground(TEXT_MUTED);
        north.add(info, BorderLayout.CENTER);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        form.setBorder(new EmptyBorder(4, 16, 8, 16));
        JTextField campoValor = new JTextField(moneyInputText(BigDecimal.ZERO));
        campoValor.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(14)));
        stylePdvField(campoValor);
        campoValor.setColumns(16);
        bindMoneyMaskLive(campoValor);
        JTextField campoMotivo = new JTextField();
        campoMotivo.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(14)));
        stylePdvField(campoMotivo);
        campoMotivo.setColumns(32);

        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(6, 4, 6, 8);
        g.anchor = GridBagConstraints.WEST;
        g.gridx = 0;
        g.gridy = 0;
        form.add(label("Valor (R$):"), g);
        g.gridx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        form.add(campoValor, g);
        g.fill = GridBagConstraints.NONE;
        g.weightx = 0;
        g.gridx = 0;
        g.gridy = 1;
        form.add(label("Motivo / observacao:"), g);
        g.gridx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        form.add(campoMotivo, g);

        JLabel status = new JLabel(" ");
        status.setForeground(MARKET_RED);
        status.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
        status.setBorder(new EmptyBorder(0, 16, 4, 16));

        JButton cancelar = button("Cancelar");
        JButton ok = button("Confirmar");
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        footer.setOpaque(false);
        footer.add(cancelar);
        footer.add(ok);

        Runnable confirmar = () -> {
            status.setText(" ");
            String motivo = campoMotivo.getText() == null ? "" : campoMotivo.getText().trim();
            if (motivo.isEmpty()) {
                status.setText("Informe o motivo ou observacao.");
                campoMotivo.requestFocusInWindow();
                return;
            }
            BigDecimal valorOperacao;
            try {
                valorOperacao = money(campoValor.getText());
            } catch (Exception ex) {
                status.setText("Valor invalido. Use apenas numeros; a formatacao e automatica.");
                campoValor.requestFocusInWindow();
                return;
            }
            try {
                BusinessRules.requirePositive(valorOperacao, "Valor da operacao");
            } catch (Exception ex) {
                status.setText("O valor precisa ser maior que zero.");
                campoValor.requestFocusInWindow();
                return;
            }
            out[0] = new ValorMotivoCaixaOperacao(valorOperacao, motivo);
            dialog.dispose();
        };

        ok.addActionListener(e -> confirmar.run());
        cancelar.addActionListener(e -> dialog.dispose());
        campoValor.addActionListener(e -> confirmar.run());
        campoMotivo.addActionListener(e -> confirmar.run());

        JPanel root = new JPanel(new BorderLayout());
        root.setOpaque(false);
        root.add(north, BorderLayout.NORTH);
        root.add(form, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(status, BorderLayout.NORTH);
        south.add(footer, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(scale(440), scale(220)));
        dialog.setLocationRelativeTo(frame);
        SwingUtilities.invokeLater(campoValor::requestFocusInWindow);
        dialog.setVisible(true);
        return out[0];
    }

    private void caixaOperacao(String tipo) {
        try {
            requirePdvAccess();
            Item caixa = (Item) caixaCombo.getSelectedItem();
            if (caixa == null) {
                msg("Selecione um caixa.");
                return;
            }
            Map<String, Object> caixaAtual = one("select status from caixas where id=?", caixa.id);
            if (caixaAtual == null || !"ABERTO".equals(String.valueOf(caixaAtual.get("status")))) {
                msg("Abra o caixa antes de registrar " + ("SANGRIA".equals(tipo) ? "sangria" : "suprimento") + ".");
                return;
            }
            String titulo = "SANGRIA".equals(tipo) ? "Sangria (retirada do caixa)" : "Suprimento (entrada no caixa)";
            ValorMotivoCaixaOperacao vm = dialogValorEMotivoSuprimentoSangria(titulo);
            if (vm == null) {
                return;
            }
            BigDecimal valorOperacao = vm.valor();
            String motivo = vm.motivo();
            if ("SANGRIA".equals(tipo)) {
                BigDecimal saldoDinheiro = saldoDinheiroFisicoNoCaixa(caixa.id);
                if (valorOperacao.compareTo(saldoDinheiro) > 0) {
                    throw new AppException("Sangria maior que o dinheiro disponivel no caixa ("
                            + moneyText(saldoDinheiro) + ").");
                }
            }
            update("insert into caixa_operacoes (caixa_id, tipo, valor, motivo, operador_id, timestamp) values (?, ?, ?, ?, ?, ?)",
                    caixa.id, tipo, valorOperacao, motivo, user.id, LocalDateTime.now().toString());
            audit(tipo, motivo);
            pdvHeaderRefresher.run();
            msg("Operacao registrada.");
        } catch (Exception ex) {
            error(ex);
        }
    }

    private void cancelarUltima() {
        try {
            requirePdvAccess();
            String pin = JOptionPane.showInputDialog(frame, "PIN do gerente:");
            if (pin == null) {
                return;
            }
            if (!managerPin(pin)) {
                msg("PIN invalido.");
                return;
            }
            String motivo = JOptionPane.showInputDialog(frame, "Motivo do cancelamento:");
            if (motivo == null) {
                return;
            }
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
            // Fundo (dinheiro do dia), Cartao e PIX no topo do PDV.
            pdvHeaderRefresher.run();
            convenioRefresher.run();
            msg("Venda cancelada.");
        } catch (Exception ex) { error(ex); }
    }

    private void processarTrocaOuDevolucao() {
        try {
            requirePdvAccess();
            String pin = JOptionPane.showInputDialog(frame, "PIN do gerente:");
            if (pin == null) {
                return;
            }
            if (!managerPin(pin)) {
                msg("PIN invalido.");
                return;
            }
            String vendaIdTexto = JOptionPane.showInputDialog(frame, "ID da venda original:");
            if (vendaIdTexto == null) {
                return;
            }
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
            Item caixa = (Item) caixaCombo.getSelectedItem();
            if (caixa == null) {
                msg("Selecione um caixa.");
                return;
            }
            Map<String, Object> abInfo = one("select abertura_timestamp from caixas where id=?", caixa.id);
            LocalDate diaAbertura = parseCaixaAberturaParaLocalDate(abInfo == null ? null : abInfo.get("abertura_timestamp"));
            if (diaAbertura != null && !diaAbertura.equals(LocalDate.now())) {
                int op = JOptionPane.showConfirmDialog(
                        frame,
                        "<html>O caixa foi aberto em <b>" + diaAbertura.format(BR_DATE) + "</b>. "
                                + "A operacao recomendada e abrir e fechar no mesmo dia civil.<br><br>"
                                + "Confirma o fechamento mesmo assim?</html>",
                        "Fechar caixa",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (op != JOptionPane.OK_OPTION) {
                    return;
                }
            }
            String contado = JOptionPane.showInputDialog(frame, "Dinheiro contado:");
            if (contado == null) {
                return;
            }
            Map<String, BigDecimal> resumo = cashReportService.sessionSummarySinceOpening(caixa.id);
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
            abrirDialogAjusteEstoqueComBusca();
        } catch (Exception ex) { error(ex); }
    }

    private void abrirDialogAjusteEstoqueComBusca() {
        List<Map<String, Object>> produtos;
        try {
            produtos = rows("""
                    select p.id as ID,
                           p.nome as Produto,
                           coalesce(p.codigo_barras, '') as Codigo,
                           coalesce(p.sku, '') as SKU,
                           coalesce(p.categoria, '') as Categoria,
                           coalesce(p.marca, '') as Marca,
                           coalesce(p.estoque_atual, 0) as Estoque
                      from produtos p
                     where p.ativo = 1
                     order by p.nome
                    """);
        } catch (Exception ex) {
            error(ex);
            return;
        }
        if (produtos.isEmpty()) {
            msg("Nenhum produto ativo cadastrado para ajustar estoque.");
            return;
        }

        JDialog dialog = new JDialog(frame, "Ajustar estoque - buscar item", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.getContentPane().setBackground(PANEL_BG);
        dialog.setSize(scale(900), scale(560));
        dialog.setMinimumSize(new Dimension(scale(680), scale(430)));
        dialog.setLocationRelativeTo(frame);

        JPanel top = new JPanel(new BorderLayout(8, 4));
        top.setOpaque(false);
        top.setBorder(new EmptyBorder(10, 10, 0, 10));
        JLabel info = new JLabel("Busque o produto por nome, codigo, SKU, categoria ou marca. Depois informe o ajuste.");
        info.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));
        info.setForeground(TEXT_MUTED);
        JTextField busca = createPlaceholderField("Buscar item para ajustar...");
        stylePdvField(busca);
        JLabel status = new JLabel(" ");
        status.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
        status.setForeground(TEXT_MUTED);
        top.add(info, BorderLayout.NORTH);
        top.add(busca, BorderLayout.CENTER);
        top.add(status, BorderLayout.SOUTH);

        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"ID", "Produto", "Codigo", "SKU", "Categoria", "Marca", "Estoque"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        for (Map<String, Object> p : produtos) {
            model.addRow(new Object[]{
                    p.get("ID"),
                    p.get("Produto"),
                    p.get("Codigo"),
                    p.get("SKU"),
                    p.get("Categoria"),
                    p.get("Marca"),
                    BR_NUMBER.format(money(String.valueOf(p.get("Estoque"))))
            });
        }

        JTable table = new JTable(model);
        styleTable(table);
        table.setRowHeight(compactMode() ? 24 : 28);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        table.getColumnModel().getColumn(0).setMinWidth(0);
        table.getColumnModel().getColumn(0).setMaxWidth(0);
        table.getColumnModel().getColumn(0).setPreferredWidth(0);
        if (table.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }

        JComboBox<String> tipo = new JComboBox<>(new String[]{"ENTRADA", "SAIDA", "AJUSTE"});
        JTextField quantidade = new JTextField();
        JTextField motivo = new JTextField();
        stylePdvField(quantidade);
        stylePdvField(motivo);
        motivo.setText("Ajuste operacional de estoque");

        JLabel selecionado = new JLabel("Produto selecionado: -");
        selecionado.setFont(new Font("Segoe UI", Font.BOLD, fontSize(12)));
        selecionado.setForeground(TEXT_DARK);

        Runnable updateSelected = () -> {
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) {
                selecionado.setText("Produto selecionado: -");
                return;
            }
            int modelRow = table.convertRowIndexToModel(viewRow);
            selecionado.setText("Produto selecionado: #" + model.getValueAt(modelRow, 0)
                    + " - " + model.getValueAt(modelRow, 1)
                    + " | estoque " + model.getValueAt(modelRow, 6));
        };
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelected.run();
            }
        });
        updateSelected.run();

        Runnable applySearch = () -> {
            List<String> terms = normalizedSearchTerms(busca.getText());
            if (terms.isEmpty()) {
                sorter.setRowFilter(null);
            } else {
                sorter.setRowFilter(new RowFilter<>() {
                    @Override
                    public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                        StringBuilder searchable = new StringBuilder();
                        for (int i = 0; i < entry.getValueCount(); i++) {
                            Object value = entry.getValue(i);
                            if (value != null) {
                                searchable.append(' ').append(value);
                            }
                        }
                        String haystack = normalizeSearchText(searchable.toString());
                        return terms.stream().allMatch(haystack::contains);
                    }
                });
            }
            status.setText(table.getRowCount() + " de " + model.getRowCount() + " produtos encontrados.");
            if (table.getRowCount() > 0) {
                table.setRowSelectionInterval(0, 0);
            }
            updateSelected.run();
        };
        busca.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applySearch.run(); }
            @Override public void removeUpdate(DocumentEvent e) { applySearch.run(); }
            @Override public void changedUpdate(DocumentEvent e) { applySearch.run(); }
        });
        busca.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "focusTable");
        busca.getActionMap().put("focusTable", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (table.getRowCount() > 0) {
                    table.requestFocusInWindow();
                    table.setRowSelectionInterval(0, 0);
                }
            }
        });
        busca.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "focusQuantity");
        busca.getActionMap().put("focusQuantity", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (table.getRowCount() > 0) {
                    table.setRowSelectionInterval(Math.max(0, table.getSelectedRow()), Math.max(0, table.getSelectedRow()));
                    quantidade.requestFocusInWindow();
                    quantidade.selectAll();
                }
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    quantidade.requestFocusInWindow();
                    quantidade.selectAll();
                }
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        form.setBorder(new EmptyBorder(0, 10, 10, 10));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridy = 0;
        gc.gridx = 0;
        gc.gridwidth = 4;
        gc.weightx = 1;
        form.add(selecionado, gc);
        gc.gridy++;
        gc.gridwidth = 1;
        gc.weightx = 0;
        form.add(label("Tipo"), gc);
        gc.gridx = 1;
        gc.weightx = 0.2;
        form.add(tipo, gc);
        gc.gridx = 2;
        gc.weightx = 0;
        form.add(label("Quantidade"), gc);
        gc.gridx = 3;
        gc.weightx = 0.4;
        form.add(quantidade, gc);
        gc.gridy++;
        gc.gridx = 0;
        gc.weightx = 0;
        form.add(label("Motivo"), gc);
        gc.gridx = 1;
        gc.gridwidth = 3;
        gc.weightx = 1;
        form.add(motivo, gc);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        footer.setOpaque(false);
        JButton cancelar = button("Cancelar");
        cancelar.addActionListener(e -> dialog.dispose());
        JButton registrar = button("Registrar ajuste");
        registrar.addActionListener(e -> {
            try {
                int viewRow = table.getSelectedRow();
                if (viewRow < 0) {
                    throw new AppException("Selecione um produto.");
                }
                int modelRow = table.convertRowIndexToModel(viewRow);
                Object produtoId = model.getValueAt(modelRow, 0);
                String tipoSelecionado = tipo.getSelectedItem().toString();
                BigDecimal qtd = money(quantidade.getText());
                BusinessRules.requirePositive(qtd, "Quantidade");
                BusinessRules.requireNotBlank(motivo.getText(), "Motivo");
                inventoryService.registerMovement(
                        ((Number) produtoId).longValue(),
                        tipoSelecionado,
                        qtd,
                        motivo.getText(),
                        user.id
                );
                audit("AJUSTE_ESTOQUE", "Produto " + produtoId + " | " + tipoSelecionado);
                dialog.dispose();
                refreshEstoqueViews();
            } catch (Exception ex) {
                error(ex);
            }
        });
        footer.add(cancelar);
        footer.add(registrar);

        applySearch.run();
        dialog.add(top, BorderLayout.NORTH);
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 0));
        bottom.setOpaque(false);
        bottom.add(form, BorderLayout.CENTER);
        bottom.add(footer, BorderLayout.SOUTH);
        dialog.add(bottom, BorderLayout.SOUTH);
        SwingUtilities.invokeLater(busca::requestFocusInWindow);
        dialog.setVisible(true);
    }

    private void reconciliarInventario() {
        try {
            requireInventoryAccess();
            String produtoId = JOptionPane.showInputDialog(frame, "ID do produto:");
            if (produtoId == null) {
                return;
            }
            String saldoContado = JOptionPane.showInputDialog(frame, "Saldo contado:");
            if (saldoContado == null) {
                return;
            }
            String motivo = JOptionPane.showInputDialog(frame, "Motivo da contagem:", "Inventario geral");
            if (motivo == null) {
                return;
            }
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
            abrirDialogPerdaQuebraComBusca();
        } catch (Exception ex) { error(ex); }
    }

    private void abrirDialogPerdaQuebraComBusca() {
        List<Map<String, Object>> produtos;
        try {
            produtos = rows("""
                    select p.id as ID,
                           p.nome as Produto,
                           coalesce(p.codigo_barras, '') as Codigo,
                           coalesce(p.sku, '') as SKU,
                           coalesce(p.categoria, '') as Categoria,
                           coalesce(p.marca, '') as Marca,
                           coalesce(p.estoque_atual, 0) as Estoque
                      from produtos p
                     where p.ativo = 1
                     order by p.nome
                    """);
        } catch (Exception ex) {
            error(ex);
            return;
        }
        if (produtos.isEmpty()) {
            msg("Nenhum produto ativo cadastrado para registrar perda ou quebra.");
            return;
        }

        JDialog dialog = new JDialog(frame, "Registrar perda/quebra - buscar item", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.getContentPane().setBackground(PANEL_BG);
        dialog.setSize(scale(900), scale(560));
        dialog.setMinimumSize(new Dimension(scale(680), scale(430)));
        dialog.setLocationRelativeTo(frame);

        JPanel top = new JPanel(new BorderLayout(8, 4));
        top.setOpaque(false);
        top.setBorder(new EmptyBorder(10, 10, 0, 10));
        JLabel info = new JLabel("Busque o produto por nome, codigo, SKU, categoria ou marca. Depois informe quantidade e motivo.");
        info.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));
        info.setForeground(TEXT_MUTED);
        JTextField busca = createPlaceholderField("Buscar item...");
        stylePdvField(busca);
        JLabel status = new JLabel(" ");
        status.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
        status.setForeground(TEXT_MUTED);
        top.add(info, BorderLayout.NORTH);
        top.add(busca, BorderLayout.CENTER);
        top.add(status, BorderLayout.SOUTH);

        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"ID", "Produto", "Codigo", "SKU", "Categoria", "Marca", "Estoque"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        for (Map<String, Object> p : produtos) {
            model.addRow(new Object[]{
                    p.get("ID"),
                    p.get("Produto"),
                    p.get("Codigo"),
                    p.get("SKU"),
                    p.get("Categoria"),
                    p.get("Marca"),
                    BR_NUMBER.format(money(String.valueOf(p.get("Estoque"))))
            });
        }

        JTable table = new JTable(model);
        styleTable(table);
        table.setRowHeight(compactMode() ? 24 : 28);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        table.getColumnModel().getColumn(0).setMinWidth(0);
        table.getColumnModel().getColumn(0).setMaxWidth(0);
        table.getColumnModel().getColumn(0).setPreferredWidth(0);
        if (table.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }

        JComboBox<Item> categoriaBaixa = new JComboBox<>(new Item[]{
                new Item(1, "VENCIMENTO - produto vencido/descarte"),
                new Item(2, "AVARIA_QUEBRA - avaria ou quebra"),
                new Item(3, "USO_INTERNO - degustacao/uso interno"),
                new Item(4, "FURTO - furto/perda identificada")
        });
        JTextField quantidade = new JTextField();
        JTextField motivo = new JTextField();
        stylePdvField(quantidade);
        stylePdvField(motivo);
        motivo.setText("Baixa operacional de estoque");

        JLabel selecionado = new JLabel("Produto selecionado: -");
        selecionado.setFont(new Font("Segoe UI", Font.BOLD, fontSize(12)));
        selecionado.setForeground(TEXT_DARK);

        Runnable updateSelected = () -> {
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) {
                selecionado.setText("Produto selecionado: -");
                return;
            }
            int modelRow = table.convertRowIndexToModel(viewRow);
            selecionado.setText("Produto selecionado: #" + model.getValueAt(modelRow, 0)
                    + " - " + model.getValueAt(modelRow, 1)
                    + " | estoque " + model.getValueAt(modelRow, 6));
        };
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelected.run();
            }
        });
        updateSelected.run();

        Runnable applySearch = () -> {
            List<String> terms = normalizedSearchTerms(busca.getText());
            if (terms.isEmpty()) {
                sorter.setRowFilter(null);
            } else {
                sorter.setRowFilter(new RowFilter<>() {
                    @Override
                    public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                        StringBuilder searchable = new StringBuilder();
                        for (int i = 0; i < entry.getValueCount(); i++) {
                            Object value = entry.getValue(i);
                            if (value != null) {
                                searchable.append(' ').append(value);
                            }
                        }
                        String haystack = normalizeSearchText(searchable.toString());
                        return terms.stream().allMatch(haystack::contains);
                    }
                });
            }
            status.setText(table.getRowCount() + " de " + model.getRowCount() + " produtos encontrados.");
            if (table.getRowCount() > 0) {
                table.setRowSelectionInterval(0, 0);
            }
            updateSelected.run();
        };
        busca.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applySearch.run(); }
            @Override public void removeUpdate(DocumentEvent e) { applySearch.run(); }
            @Override public void changedUpdate(DocumentEvent e) { applySearch.run(); }
        });
        busca.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "focusTable");
        busca.getActionMap().put("focusTable", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (table.getRowCount() > 0) {
                    table.requestFocusInWindow();
                    table.setRowSelectionInterval(0, 0);
                }
            }
        });
        busca.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "focusQuantity");
        busca.getActionMap().put("focusQuantity", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (table.getRowCount() > 0) {
                    table.setRowSelectionInterval(Math.max(0, table.getSelectedRow()), Math.max(0, table.getSelectedRow()));
                    quantidade.requestFocusInWindow();
                    quantidade.selectAll();
                }
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    quantidade.requestFocusInWindow();
                    quantidade.selectAll();
                }
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        form.setBorder(new EmptyBorder(0, 10, 10, 10));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridy = 0;
        gc.gridx = 0;
        gc.gridwidth = 4;
        gc.weightx = 1;
        form.add(selecionado, gc);
        gc.gridy++;
        gc.gridwidth = 1;
        gc.weightx = 0;
        form.add(label("Motivo da baixa"), gc);
        gc.gridx = 1;
        gc.weightx = 0.2;
        form.add(categoriaBaixa, gc);
        gc.gridx = 2;
        gc.weightx = 0;
        form.add(label("Quantidade"), gc);
        gc.gridx = 3;
        gc.weightx = 0.4;
        form.add(quantidade, gc);
        gc.gridy++;
        gc.gridx = 0;
        gc.weightx = 0;
        form.add(label("Motivo"), gc);
        gc.gridx = 1;
        gc.gridwidth = 3;
        gc.weightx = 1;
        form.add(motivo, gc);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        footer.setOpaque(false);
        JButton cancelar = button("Cancelar");
        cancelar.addActionListener(e -> dialog.dispose());
        JButton registrar = button("Registrar perda/quebra");
        registrar.addActionListener(e -> {
            try {
                int viewRow = table.getSelectedRow();
                if (viewRow < 0) {
                    throw new AppException("Selecione um produto.");
                }
                int modelRow = table.convertRowIndexToModel(viewRow);
                Object produtoId = model.getValueAt(modelRow, 0);
                BigDecimal qtd = money(quantidade.getText());
                BusinessRules.requirePositive(qtd, "Quantidade");
                BusinessRules.requireNotBlank(motivo.getText(), "Motivo");
                Item categoriaItem = (Item) categoriaBaixa.getSelectedItem();
                String categoriaSelecionada = categoriaItem == null ? "PERDA" : categoriaItem.text().split(" - ", 2)[0];
                DesktopInventoryService.StockLossResult result = inventoryService.registerStockLoss(
                        new DesktopInventoryService.StockLossRequest(
                                ((Number) produtoId).longValue(),
                                qtd,
                                categoriaSelecionada,
                                motivo.getText()
                        ),
                        user.id
                );
                audit("PERDA_QUEBRA", "Produto " + produtoId + " | " + result.categoria()
                        + " | movimento " + result.movimentoTipo()
                        + " | valor " + moneyText(result.valorPerda())
                        + " | financeiro #" + result.lancamentoFinanceiroId());
                dialog.dispose();
                refreshFrame();
            } catch (Exception ex) {
                error(ex);
            }
        });
        footer.add(cancelar);
        footer.add(registrar);

        applySearch.run();
        dialog.add(top, BorderLayout.NORTH);
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 0));
        bottom.setOpaque(false);
        bottom.add(form, BorderLayout.CENTER);
        bottom.add(footer, BorderLayout.SOUTH);
        dialog.add(bottom, BorderLayout.SOUTH);
        SwingUtilities.invokeLater(busca::requestFocusInWindow);
        dialog.setVisible(true);
    }

    private void importarXml() {
        JFileChooser chooser = new JFileChooser(xmlInboxDir().toFile());
        chooser.setDialogTitle("Selecionar XML NF-e (um ou varios arquivos)");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Arquivos XML", "xml"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File[] chosen = chooser.getSelectedFiles();
        if (chosen == null || chosen.length == 0) {
            return;
        }
        try {
            requireInventoryAccess();
            int ok = 0;
            StringBuilder falhas = new StringBuilder();
            for (File sel : chosen) {
                try {
                    File file = persistXmlInInbox(sel);
                    registrarXmlNfePendente(file);
                    ok++;
                } catch (Exception ex) {
                    if (falhas.length() > 0) {
                        falhas.append('\n');
                    }
                    falhas.append(sel.getName()).append(": ").append(friendlyMessage(ex));
                }
            }
            if (ok > 0 && falhas.length() == 0) {
                msg(ok == 1
                        ? "1 XML colocado na fila. Revise a lista e use Dar baixa no estoque para dar entrada nos produtos."
                        : ok + " XML colocados na fila. Revise a lista e use Dar baixa no estoque para dar entrada nos produtos.");
            } else if (ok > 0) {
                msg(ok + " importado(s) com sucesso.\n\nErros:\n" + falhas);
            } else {
                msg("Nenhum XML importado.\n\n" + falhas);
            }
            refreshFrame();
        } catch (Exception ex) {
            error(ex);
        }
    }

    /** Chave de 44 digitos da NF-e (atributo Id do infNFe, sem prefixo NFe). */
    private String chaveAcessoNFe(org.w3c.dom.Document doc) {
        var list = doc.getElementsByTagName("infNFe");
        if (list.getLength() == 0) {
            return "";
        }
        var inf = (Element) list.item(0);
        String id = inf.getAttribute("Id");
        if (id == null) {
            return "";
        }
        id = id.trim();
        if (id.regionMatches(true, 0, "NFe", 0, 3)) {
            return id.substring(3);
        }
        return id;
    }

    /**
     * Busca fornecedor pelo CNPJ do emitente da NF-e. Compara apenas digitos para
     * coincidir com CNPJ gravado formatado ou sem mascara no cadastro.
     */
    private Long findFornecedorIdByCnpjFromNfeXml(String cnpjTag) throws Exception {
        String digits = digitsOnlyFornecedor(cnpjTag);
        if (digits.isEmpty()) {
            return null;
        }
        if (digits.length() == 14) {
            Map<String, Object> row = one("select id from fornecedores where cnpj = ?", formatCnpjUi(digits));
            if (row != null && row.get("id") != null) {
                return ((Number) row.get("id")).longValue();
            }
        }
        Map<String, Object> row = one("select id from fornecedores where cnpj = ?", digits);
        if (row != null && row.get("id") != null) {
            return ((Number) row.get("id")).longValue();
        }
        String trimmed = cnpjTag == null ? "" : cnpjTag.trim();
        if (!trimmed.isEmpty() && !trimmed.equals(digits)) {
            row = one("select id from fornecedores where cnpj = ?", trimmed);
            if (row != null && row.get("id") != null) {
                return ((Number) row.get("id")).longValue();
            }
        }
        for (Map<String, Object> r : rows("select id, cnpj from fornecedores where cnpj is not null and trim(cnpj) != ''")) {
            if (digits.equals(digitsOnlyFornecedor(Objects.toString(r.get("cnpj"), "")))) {
                return ((Number) r.get("id")).longValue();
            }
        }
        return null;
    }

    /**
     * Le o XML, cadastra fornecedor somente se ainda nao existir (por CNPJ) e grava a NF como PENDENTE.
     * Nao movimenta estoque — isso ocorre em {@link #processarBaixaXmlNfe(long, org.w3c.dom.Document, java.util.List)}.
     * Se a mesma chave (ou o mesmo arquivo) ja tiver sido baixada, pergunta se deseja importar de novo para nova baixa.
     */
    private void registrarXmlNfePendente(File file) throws Exception {
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
        doc.getDocumentElement().normalize();
        String chave = chaveAcessoNFe(doc);
        String abs = file.getAbsolutePath();
        Map<String, Object> dupXml = one("select id from notas_fiscais where status='PENDENTE' and xml_path=?", abs);
        if (dupXml != null && !dupXml.isEmpty()) {
            throw new AppException("Este arquivo ja esta na fila pendente. Exclua da lista ou de baixa antes de importar de novo.");
        }
        if (!chave.isBlank()) {
            Map<String, Object> dupChave = one(
                    "select id from notas_fiscais where status='PENDENTE' and coalesce(chave_acesso,'')=?", chave);
            if (dupChave != null && !dupChave.isEmpty()) {
                throw new AppException("Esta NF-e (chave de acesso) ja esta na fila pendente.");
            }
        }
        Map<String, Object> jaBaixada = null;
        if (!chave.isBlank()) {
            jaBaixada = one("""
                    select id, coalesce(numero_nf, '') as numero_nf
                      from notas_fiscais
                     where status = 'BAIXADO' and coalesce(chave_acesso, '') = ?
                     order by id desc limit 1
                    """, chave);
        }
        if (jaBaixada == null || jaBaixada.isEmpty()) {
            jaBaixada = one("""
                    select id, coalesce(numero_nf, '') as numero_nf
                      from notas_fiscais
                     where status = 'BAIXADO' and xml_path = ?
                     order by id desc limit 1
                    """, abs);
        }
        if (jaBaixada != null && !jaBaixada.isEmpty()) {
            String nfr = Objects.toString(jaBaixada.get("numero_nf"), "").trim();
            long nid = ((Number) jaBaixada.get("id")).longValue();
            StringBuilder aviso = new StringBuilder();
            aviso.append("Esta NF-e ja teve baixa registrada no sistema (registro anterior id ").append(nid);
            if (!nfr.isBlank()) {
                aviso.append(", NF ").append(nfr);
            }
            aviso.append(").\n\nSe importar de novo, a nota volta para a fila e podera ser baixada outra vez ")
                    .append("(nova entrada de estoque e, se registrar financeiro, nova conta a pagar — cuidado com duplicidade).\n\n")
                    .append("Deseja importar mesmo assim para permitir nova baixa?");
            int op = JOptionPane.showConfirmDialog(frame, aviso.toString(), "NF-e ja baixada",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (op != JOptionPane.YES_OPTION) {
                throw new AppException("Importacao cancelada: esta NF-e ja tinha baixa no sistema.");
            }
        }
        String cnpjTag = first(doc, "CNPJ");
        String fornecedor = first(doc, "xNome").trim();
        String rz = fornecedor.isBlank() ? "Fornecedor NF-e" : fornecedor;
        String nfFant = fornecedor.isBlank() ? rz : fornecedor;

        Long existente = findFornecedorIdByCnpjFromNfeXml(cnpjTag);
        long fornecedorId;
        if (existente != null) {
            fornecedorId = existente;
        } else {
            String digits = digitsOnlyFornecedor(cnpjTag);
            String cnpjGravar;
            if (digits.length() == 14) {
                cnpjGravar = formatCnpjUi(digits);
            } else if (!cnpjTag.isEmpty()) {
                cnpjGravar = cnpjTag.trim();
            } else {
                cnpjGravar = null;
            }
            fornecedorId = insert(
                    "insert into fornecedores (razao_social, nome_fantasia, cnpj) values (?, ?, ?)",
                    rz, nfFant, cnpjGravar);
            if (fornecedorId <= 0) {
                throw new AppException("Nao foi possivel cadastrar o fornecedor.");
            }
        }
        var dets = doc.getElementsByTagName("det");
        if (dets.getLength() == 0) {
            throw new AppException("XML sem itens (tags det). Verifique se e uma NF-e valida.");
        }
        update("""
                insert into notas_fiscais (fornecedor_id, numero_nf, data, xml_path, total, importado_em, chave_acesso, status)
                values (?, ?, ?, ?, ?, ?, ?, 'PENDENTE')
                """,
                fornecedorId, first(doc, "nNF"), first(doc, "dhEmi"), abs, money(first(doc, "vNF")),
                LocalDateTime.now().toString(), chave.isBlank() ? null : chave);
        audit("XML_NFE_FILA", "Arquivo " + file.getName() + " | itens_xml=" + dets.getLength());
    }

    /**
     * GTIN da tag {@code cEAN} / {@code cEANTrib}: ignora vazio, "0" e "SEM GTIN"
     * (comum em NF-e quando o produto nao tem codigo de barras padrao).
     */
    private static String normalizarGtinNfe(String cean) {
        if (cean == null) {
            return null;
        }
        String t = cean.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("SEM GTIN") || "0".equals(t)) {
            return null;
        }
        return t;
    }

    private static String unidadeOuPadraoNfe(String uCom) {
        if (uCom == null || uCom.trim().isEmpty()) {
            return "un";
        }
        String t = uCom.trim();
        if (t.length() > 12) {
            return t.substring(0, 12).toLowerCase(Locale.ROOT);
        }
        return t.toLowerCase(Locale.ROOT);
    }

    /**
     * Localiza produto ja cadastrado: primeiro pelo codigo de barras (GTIN),
     * depois pelo codigo do item na NF-e ({@code cProd} / SKU).
     */
    private Map<String, Object> buscarProdutoParaItemNfe(String barrasNorm, String cProd) throws Exception {
        if (barrasNorm != null && !barrasNorm.isBlank()) {
            Map<String, Object> porBarras = one("select id from produtos where codigo_barras = ?", barrasNorm);
            if (porBarras != null) {
                return porBarras;
            }
        }
        if (cProd != null && !cProd.isBlank()) {
            return one("select id from produtos where trim(coalesce(sku,'')) = ?", cProd.trim());
        }
        return null;
    }

    /**
     * Registra linha em {@code entradas_estoque} na mesma transacao da baixa do XML
     * (espelha a entrada manual para aparecer no historico de entradas / lote).
     */
    private void registrarEntradaEstoquePorXmlNfe(long produtoId, BigDecimal quantidade, BigDecimal custoUnitario,
            String lote, String validade, String documentoRef, long notaFiscalId) throws Exception {
        String obs = "Entrada XML NF-e (baixa nota id=" + notaFiscalId + ")";
        update("""
                insert into entradas_estoque (produto_id, quantidade, custo_unitario, lote, validade, documento, observacao, operador_id, criado_em)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                produtoId,
                quantidade,
                custoUnitario.setScale(2, RoundingMode.HALF_UP),
                nullIfBlank(lote),
                nullIfBlank(validade),
                documentoRef.isBlank() ? null : documentoRef,
                obs,
                user.id,
                LocalDateTime.now().toString());
    }

    /** Aplica itens da NF-e no estoque (produtos, entradas_estoque e movimentacao). Usado na baixa da fila. */
    private int aplicarItensNfeNoEstoque(org.w3c.dom.Document doc, long fornecedorId, long notaFiscalId) throws Exception {
        String chaveDoc = chaveAcessoNFe(doc);
        String nNF = first(doc, "nNF").trim();
        String documentoRef = !chaveDoc.isBlank() ? chaveDoc
                : (!nNF.isBlank() ? ("NF " + nNF) : ("nota_fiscal_id=" + notaFiscalId));
        var dets = doc.getElementsByTagName("det");
        for (int i = 0; i < dets.getLength(); i++) {
            Element det = (Element) dets.item(i);
            Element prod = (Element) det.getElementsByTagName("prod").item(0);
            if (prod == null) {
                throw new AppException("Item " + (i + 1) + " da NF-e sem tag <prod>.");
            }
            String ceanRaw = text(prod, "cEAN");
            if (ceanRaw.isEmpty()) {
                ceanRaw = text(prod, "cEANTrib");
            }
            String barrasNorm = normalizarGtinNfe(ceanRaw);
            String cProd = text(prod, "cProd");
            String nome = text(prod, "xProd");
            String ncm = nullIfBlank(text(prod, "NCM"));
            String cest = nullIfBlank(text(prod, "CEST"));
            String uComRaw = text(prod, "uCom");
            if (uComRaw.isEmpty()) {
                uComRaw = text(prod, "uTrib");
            }
            String uCom = unidadeOuPadraoNfe(uComRaw);
            String nLote = nullIfBlank(text(prod, "nLote"));
            String dVal = nullIfBlank(text(prod, "dVal"));
            String qStr = text(prod, "qCom");
            if (qStr.isEmpty()) {
                qStr = text(prod, "qTrib");
            }
            String vUnStr = text(prod, "vUnCom");
            if (vUnStr.isEmpty()) {
                vUnStr = text(prod, "vUnTrib");
            }
            String vProdStr = text(prod, "vProd");
            if (vUnStr.isEmpty() && !qStr.isEmpty() && !vProdStr.isEmpty()) {
                try {
                    BigDecimal vProd = money(vProdStr);
                    BigDecimal qTry = money(qStr);
                    if (qTry.compareTo(BigDecimal.ZERO) > 0) {
                        vUnStr = vProd.divide(qTry, 10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
                    }
                } catch (Exception ignored) {
                    // mantem vUnStr vazio; validacao abaixo falha com mensagem clara
                }
            }
            BigDecimal qtd = money(qStr);
            BigDecimal custo = money(vUnStr);
            BusinessRules.requireNotBlank(nome, "Produto da NF-e");
            BusinessRules.requirePositive(qtd, "Quantidade da NF-e");
            BusinessRules.requireNonNegative(custo, "Custo da NF-e");
            String skuInsert = !cProd.isBlank() ? cProd.trim() : barrasNorm;
            Map<String, Object> existente = buscarProdutoParaItemNfe(barrasNorm, cProd);
            if (existente == null) {
                String codigoInterno = nextInternalCode();
                long produtoId = insert("""
                    insert into produtos (nome, codigo_barras, sku, codigo_interno, categoria, unidade, ncm, cest,
                        preco_custo, preco_venda, estoque_atual, estoque_minimo, fornecedor_id, validade, lote_padrao, observacoes, ativo)
                    values (?, ?, ?, ?, 'Mercearia', ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, 1)
                    """,
                        nome,
                        barrasNorm,
                        skuInsert,
                        codigoInterno,
                        uCom,
                        ncm,
                        cest,
                        custo,
                        markupPrice(custo),
                        qtd,
                        fornecedorId,
                        dVal,
                        nLote,
                        "Criado por importacao XML NF-e");
                recordPriceHistory(produtoId, null, custo, null, markupPrice(custo), "Cadastro por XML NF-e");
                update("insert into movimentacao_estoque (produto_id, tipo, quantidade, operador_id, timestamp, observacao) values (?, 'ENTRADA', ?, ?, ?, ?)",
                        produtoId, qtd, user.id, LocalDateTime.now().toString(), "XML NF-e");
                registrarEntradaEstoquePorXmlNfe(produtoId, qtd, custo, nLote, dVal, documentoRef, notaFiscalId);
            } else {
                long produtoId = ((Number) existente.get("id")).longValue();
                Map<String, Object> produtoAtual = one("select estoque_atual, preco_custo, preco_venda from produtos where id=?", produtoId);
                BigDecimal custoMedio = weightedAverageCost(money(produtoAtual.get("estoque_atual").toString()), money(produtoAtual.get("preco_custo").toString()), qtd, custo);
                recordPriceHistory(produtoId, money(produtoAtual.get("preco_custo").toString()), custoMedio,
                        money(produtoAtual.get("preco_venda").toString()), money(produtoAtual.get("preco_venda").toString()),
                        "Atualizacao de custo por XML NF-e");
                String skuXml = cProd.isBlank() ? null : cProd.trim();
                update("""
                        update produtos set
                            nome = ?,
                            ncm = coalesce(?, ncm),
                            cest = coalesce(?, cest),
                            unidade = coalesce(?, unidade),
                            sku = coalesce(?, sku),
                            fornecedor_id = ?,
                            estoque_atual = estoque_atual + ?,
                            preco_custo = ?,
                            lote_padrao = coalesce(?, lote_padrao),
                            validade = coalesce(?, validade)
                        where id = ?
                        """,
                        nome,
                        ncm,
                        cest,
                        uCom,
                        nullIfBlank(skuXml),
                        fornecedorId,
                        qtd,
                        custoMedio,
                        nLote,
                        dVal,
                        produtoId);
                update("insert into movimentacao_estoque (produto_id, tipo, quantidade, operador_id, timestamp, observacao) values (?, 'ENTRADA', ?, ?, ?, ?)",
                        produtoId, qtd, user.id, LocalDateTime.now().toString(), "XML NF-e");
                registrarEntradaEstoquePorXmlNfe(produtoId, qtd, custo, nLote, dVal, documentoRef, notaFiscalId);
            }
        }
        return dets.getLength();
    }

    /**
     * Resultado do passo de "boleto" depois da conferencia dos itens do XML:
     * {@code pularEstaNota} pula esta nota; {@code lancamento} nulo = baixa so no estoque;
     * nao-nulo = cria conta a pagar vinculada a NF.
     */
    private record XmlNfeBoletoChoice(boolean pularEstaNota, List<DesktopFinanceService.FinanceEntryRequest> lancamentos) {
        static XmlNfeBoletoChoice pular() {
            return new XmlNfeBoletoChoice(true, List.of());
        }

        static XmlNfeBoletoChoice semFinanceiro() {
            return new XmlNfeBoletoChoice(false, List.of());
        }

        static XmlNfeBoletoChoice comLancamentos(List<DesktopFinanceService.FinanceEntryRequest> lista) {
            return new XmlNfeBoletoChoice(false, lista == null ? List.of() : lista);
        }

        static XmlNfeBoletoChoice comLancamento(DesktopFinanceService.FinanceEntryRequest r) {
            return comLancamentos(r == null ? List.of() : List.of(r));
        }
    }

    /**
     * Campos iniciais da conta a pagar na baixa do XML (mesma regra do dialogo do boleto).
     */
    private record XmlNfeFinanceirosSugeridos(
            BigDecimal valor,
            String vencimento,
            String descricao,
            String parceiro,
            String categoria,
            String observacao) {
    }

    private XmlNfeFinanceirosSugeridos sugeridosLancamentoXmlNfe(long notaId, org.w3c.dom.Document doc, long fornecedorId,
            String numeroNf, String chave, BigDecimal totalImportado) throws Exception {
        String vnf = first(doc, "vNF");
        BigDecimal totalSugerido = totalImportado;
        if (!vnf.isBlank()) {
            totalSugerido = money(vnf);
        }
        String parceiroIni = "";
        Map<String, Object> forn = one("select nome_fantasia, razao_social from fornecedores where id = ?", fornecedorId);
        if (forn != null) {
            String nf = Objects.toString(forn.get("nome_fantasia"), "").trim();
            String rz = Objects.toString(forn.get("razao_social"), "").trim();
            parceiroIni = !nf.isBlank() ? nf : rz;
        }
        if (parceiroIni.isBlank()) {
            parceiroIni = emitenteXNomeNfe(doc);
        }
        String numExibir = numeroNf.isBlank() ? first(doc, "nNF") : numeroNf;
        String descIni = "NF-e " + (numExibir.isBlank() ? "#" + notaId : numExibir) + " — conta a pagar (fornecedor)";
        String vencIni = sugerirVencimentoBoletoNfe(doc);
        String obsIni = "Gerado na baixa do XML. Nota fiscal id=" + notaId
                + (chave.isBlank() ? "" : " | chave " + chave);
        return new XmlNfeFinanceirosSugeridos(totalSugerido, vencIni, descIni, parceiroIni, "Fornecedor / NF-e", obsIni);
    }

    private String emitenteXNomeNfe(org.w3c.dom.Document doc) {
        var nl = doc.getElementsByTagName("emit");
        if (nl.getLength() == 0) {
            return "";
        }
        return text((Element) nl.item(0), "xNome");
    }

    private String sugerirVencimentoBoletoNfe(org.w3c.dom.Document doc) {
        var nl = doc.getElementsByTagName("dup");
        for (int i = 0; i < nl.getLength(); i++) {
            Element dup = (Element) nl.item(i);
            String dv = text(dup, "dVenc");
            if (!dv.isBlank()) {
                try {
                    return normalizarDataNFeParaIso(dv);
                } catch (AppException ignored) {
                    // tenta proxima parcela
                }
            }
        }
        return LocalDate.now().plusDays(30).toString();
    }

    private String normalizarDataNFeParaIso(String raw) {
        String s = raw.trim();
        if (s.length() >= 10 && s.charAt(4) == '-' && s.charAt(7) == '-') {
            LocalDate.parse(s.substring(0, 10));
            return s.substring(0, 10);
        }
        try {
            return LocalDate.parse(s, DateTimeFormatter.ofPattern("d/M/uuuu")).toString();
        } catch (DateTimeParseException e1) {
            try {
                return LocalDate.parse(s, DateTimeFormatter.ofPattern("dd/MM/uuuu")).toString();
            } catch (DateTimeParseException e2) {
                throw new AppException("Data de vencimento invalida: \"" + raw + "\". Use AAAA-MM-DD.");
            }
        }
    }

    private record NfeDupXml(String nDup, String dVencIso, BigDecimal vDup) {
    }

    private List<NfeDupXml> lerDuplicatasDocumentoNfe(org.w3c.dom.Document doc) {
        List<NfeDupXml> out = new ArrayList<>();
        var nl = doc.getElementsByTagName("dup");
        for (int i = 0; i < nl.getLength(); i++) {
            Element dup = (Element) nl.item(i);
            String dv = text(dup, "dVenc");
            if (dv.isBlank()) {
                continue;
            }
            try {
                String iso = normalizarDataNFeParaIso(dv);
                String vDupT = text(dup, "vDup");
                BigDecimal vDup = vDupT.isBlank() ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : money(vDupT);
                out.add(new NfeDupXml(text(dup, "nDup"), iso, vDup));
            } catch (AppException | DateTimeParseException ignored) {
                // duplicata ignorada
            }
        }
        return out;
    }

    /** Soma dos {@code vProd} de cada {@code det/prod} no XML (mercadorias), sem taxas finais da NF. */
    private BigDecimal somaVProdItensNfeXml(org.w3c.dom.Document doc) {
        var dets = doc.getElementsByTagName("det");
        BigDecimal acc = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (int i = 0; i < dets.getLength(); i++) {
            Element det = (Element) dets.item(i);
            Element prod = (Element) det.getElementsByTagName("prod").item(0);
            if (prod == null) {
                continue;
            }
            String vp = text(prod, "vProd");
            if (!vp.isBlank()) {
                acc = acc.add(money(vp));
            }
        }
        return acc;
    }

    private static List<BigDecimal> splitValorEmNParcelas(BigDecimal total, int n) {
        if (n < 1) {
            throw new AppException("Numero de parcelas invalido.");
        }
        total = total.setScale(2, RoundingMode.HALF_UP);
        List<BigDecimal> parts = new ArrayList<>();
        BigDecimal cada = total.divide(BigDecimal.valueOf(n), 2, RoundingMode.DOWN);
        BigDecimal acc = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (int i = 0; i < n - 1; i++) {
            parts.add(cada);
            acc = acc.add(cada);
        }
        parts.add(total.subtract(acc).setScale(2, RoundingMode.HALF_UP));
        return parts;
    }

    /** Comparacao de valores em Reais para totais da NF (tolerancia de 2 centavos). */
    private static boolean moneyQuaseIgual(BigDecimal a, BigDecimal b) {
        BigDecimal as = a.setScale(2, RoundingMode.HALF_UP);
        BigDecimal bs = b.setScale(2, RoundingMode.HALF_UP);
        return as.subtract(bs).abs().compareTo(new BigDecimal("0.02")) <= 0;
    }

    /**
     * Lista os itens do XML (somente leitura) para conferir com a nota importada
     * (numero, total, chave) e com o cadastro de produtos. Retorna {@code false}
     * se o usuario cancelar esta nota (equivalente a pular a baixa dela).
     */
    private boolean abrirDialogConferenciaItensXmlNfe(long notaId, org.w3c.dom.Document doc, long fornecedorId,
            String numeroNf, String chave, BigDecimal totalNotaRegistrado) throws Exception {
        var dets = doc.getElementsByTagName("det");
        if (dets.getLength() == 0) {
            throw new AppException("XML sem itens (tags det).");
        }
        Vector<String> cols = new Vector<>(List.of(
                "#", "Cod. NF", "GTIN", "Produto (XML)", "NCM", "CEST", "Qtd", "Un", "Custo un.", "Total linha",
                "ID", "Nome (cadastro)", "Marca", "Fabricante", "Vinculo"));
        Vector<Vector<Object>> lines = new Vector<>();
        for (int i = 0; i < dets.getLength(); i++) {
            Element det = (Element) dets.item(i);
            Element prod = (Element) det.getElementsByTagName("prod").item(0);
            if (prod == null) {
                throw new AppException("Item " + (i + 1) + " da NF-e sem tag <prod>.");
            }
            String ceanRaw = text(prod, "cEAN");
            if (ceanRaw.isEmpty()) {
                ceanRaw = text(prod, "cEANTrib");
            }
            String barrasNorm = normalizarGtinNfe(ceanRaw);
            String cProd = text(prod, "cProd");
            String nome = text(prod, "xProd");
            String ncmXml = text(prod, "NCM");
            String cestXml = text(prod, "CEST");
            String uComRaw = text(prod, "uCom");
            if (uComRaw.isEmpty()) {
                uComRaw = text(prod, "uTrib");
            }
            String uCom = unidadeOuPadraoNfe(uComRaw);
            String qStr = text(prod, "qCom");
            if (qStr.isEmpty()) {
                qStr = text(prod, "qTrib");
            }
            String vUnStr = text(prod, "vUnCom");
            if (vUnStr.isEmpty()) {
                vUnStr = text(prod, "vUnTrib");
            }
            String vProdStr = text(prod, "vProd");
            if (vUnStr.isEmpty() && !qStr.isEmpty() && !vProdStr.isEmpty()) {
                try {
                    BigDecimal vProd = money(vProdStr);
                    BigDecimal qTry = money(qStr);
                    if (qTry.compareTo(BigDecimal.ZERO) > 0) {
                        vUnStr = vProd.divide(qTry, 10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
                    }
                } catch (Exception ignored) {
                    // deixa vUnStr vazio
                }
            }
            BigDecimal qtd = money(qStr);
            BigDecimal custo = money(vUnStr);
            String vProdRaw = text(prod, "vProd");
            String totLinha = vProdRaw.isBlank() ? "—" : moneyText(money(vProdRaw));
            String gtinExibir = barrasNorm == null ? "—" : barrasNorm;
            String codExibir = cProd.isBlank() ? "—" : cProd;
            String ncmExibir = ncmXml.isBlank() ? "—" : ncmXml;
            String cestExibir = cestXml.isBlank() ? "—" : cestXml;
            Map<String, Object> match = buscarProdutoParaItemNfe(barrasNorm, cProd);
            String idCad = "—";
            String nomeCad = "—";
            String marcaCad = "—";
            String fabCad = "—";
            String vinculo;
            if (match == null) {
                vinculo = "Novo na baixa";
            } else {
                long pid = ((Number) match.get("id")).longValue();
                Map<String, Object> prow = one("""
                        select id, nome,
                               coalesce(nullif(trim(marca), ''), '') as marca,
                               coalesce(nullif(trim(fabricante), ''), '') as fabricante
                        from produtos where id = ?
                        """, pid);
                if (prow == null) {
                    vinculo = "ID " + pid + " (cadastro nao encontrado)";
                } else {
                    idCad = Objects.toString(prow.get("id"), "—");
                    nomeCad = Objects.toString(prow.get("nome"), "—");
                    marcaCad = Objects.toString(prow.get("marca"), "");
                    fabCad = Objects.toString(prow.get("fabricante"), "");
                    if (marcaCad.isBlank()) {
                        marcaCad = "—";
                    }
                    if (fabCad.isBlank()) {
                        fabCad = "—";
                    }
                    vinculo = "Cadastro existente";
                }
            }
            Vector<Object> line = new Vector<>();
            line.add(i + 1);
            line.add(codExibir);
            line.add(gtinExibir);
            line.add(nome);
            line.add(ncmExibir);
            line.add(cestExibir);
            line.add(BR_NUMBER.format(qtd));
            line.add(uCom);
            line.add(moneyText(custo));
            line.add(totLinha);
            line.add(idCad);
            line.add(nomeCad);
            line.add(marcaCad);
            line.add(fabCad);
            line.add(vinculo);
            lines.add(line);
        }
        String vnfTag = first(doc, "vNF");
        BigDecimal totalXml = vnfTag.isBlank() ? null : money(vnfTag);
        String numExibir = numeroNf.isBlank() ? first(doc, "nNF") : numeroNf;
        StringBuilder cabTxt = new StringBuilder();
        cabTxt.append("Nota fiscal no sistema (#").append(notaId).append(")");
        if (!numExibir.isBlank()) {
            cabTxt.append(" — NF ").append(numExibir);
        }
        cabTxt.append("\nTotal na importacao: ").append(moneyText(totalNotaRegistrado));
        if (totalXml != null) {
            cabTxt.append("  |  Total no XML (vNF): ").append(moneyText(totalXml));
            if (totalXml.compareTo(totalNotaRegistrado) != 0) {
                cabTxt.append("\nATENCAO: o total do XML difere do total gravado na importacao. Confira o arquivo ou reimporte.");
            }
        }
        if (!chave.isBlank()) {
            String chShort = chave.length() > 48 ? chave.substring(0, 16) + "..." + chave.substring(chave.length() - 8) : chave;
            cabTxt.append("\nChave: ").append(chShort);
        }
        Map<String, Object> forn = one("""
                select coalesce(nullif(trim(nome_fantasia), ''), nullif(trim(razao_social), ''), '') as nome
                  from fornecedores where id = ?
                """, fornecedorId);
        if (forn != null) {
            String fn = Objects.toString(forn.get("nome"), "").trim();
            if (!fn.isBlank()) {
                cabTxt.append("\nFornecedor: ").append(fn);
            }
        }
        cabTxt.append("\n\nO XML completo da NF-e tambem contem emitente, destinatario, impostos (ICMS etc.), transporte, cobranca e outros blocos. ");
        cabTxt.append("Esta tela lista os itens de mercadoria (<det>/<prod>) com NCM/CEST do XML; se o produto ja existir no cadastro (GTIN ou Cod. NF), mostram-se ID, nome, marca e fabricante para conferencia.");
        JTextArea cab = new JTextArea(cabTxt.toString());
        cab.setEditable(false);
        cab.setOpaque(false);
        cab.setLineWrap(true);
        cab.setWrapStyleWord(true);
        cab.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(13)));
        cab.setBorder(new EmptyBorder(0, 0, 6, 0));

        XmlNfeFinanceirosSugeridos sugFin = sugeridosLancamentoXmlNfe(notaId, doc, fornecedorId, numeroNf, chave, totalNotaRegistrado);
        StringBuilder finTxt = new StringBuilder();
        finTxt.append("Conta a pagar (proxima etapa) — sugestao a partir do XML:\n\n");
        finTxt.append("Valor (R$): ").append(moneyInputText(sugFin.valor())).append("\n");
        finTxt.append("Vencimento (AAAA-MM-DD): ").append(sugFin.vencimento()).append("\n");
        finTxt.append("Descricao: ").append(sugFin.descricao()).append("\n");
        finTxt.append("Parceiro: ").append(sugFin.parceiro()).append("\n");
        finTxt.append("Categoria: ").append(sugFin.categoria()).append("\n");
        finTxt.append("Observacao: ").append(sugFin.observacao());
        finTxt.append("\n\nPagamento (proxima tela): a vista ou parcelado — no parcelado, informe quantidade de parcelas, valores e vencimentos (ou use \"Preencher do XML\" se houver <dup>).");
        if (!UserPermissions.canAccessFinance(user.role)) {
            finTxt.append("\n\n(Seu perfil nao registra conta a pagar; os dados acima sao apenas informativos.)");
        }
        JTextArea finPrev = new JTextArea(finTxt.toString());
        finPrev.setEditable(false);
        finPrev.setLineWrap(true);
        finPrev.setWrapStyleWord(true);
        finPrev.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));
        finPrev.setBackground(MARKET_GREEN_SOFT);
        finPrev.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xC8, 0xE6, 0xC9)),
                new EmptyBorder(8, 10, 8, 10)));

        DefaultTableModel model = new DefaultTableModel(lines, cols) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowSorter(null);
        styleTable(table);
        var tcm = table.getColumnModel();
        int[] widths = {
                36, 120, 108, 220, 84, 72, 56, 44, 84, 84,
                40, 200, 96, 110, 108
        };
        for (int c = 0; c < Math.min(widths.length, tcm.getColumnCount()); c++) {
            tcm.getColumn(c).setPreferredWidth(widths[c]);
        }

        JLabel rodape = new JLabel("Itens deste XML (" + dets.getLength()
                + "). Em seguida abre o passo do boleto / financeiro (se aplicavel).");
        rodape.setFont(rodape.getFont().deriveFont(Font.ITALIC));
        rodape.setForeground(TEXT_MUTED);

        JPanel norte = new JPanel();
        norte.setLayout(new BoxLayout(norte, BoxLayout.Y_AXIS));
        norte.setOpaque(false);
        norte.add(cab);
        norte.add(Box.createVerticalStrut(6));
        norte.add(finPrev);
        norte.add(Box.createVerticalStrut(4));
        norte.add(rodape);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(980, compactMode() ? 220 : 300));

        final boolean[] continuar = {false};
        JDialog dialog = new JDialog(frame, "Itens do XML — conferencia com a NF", true);
        dialog.setLayout(new BorderLayout(10, 10));
        ((JPanel) dialog.getContentPane()).setBorder(new EmptyBorder(10, 12, 10, 12));
        JButton btnOk = new JButton("Continuar");
        JButton btnCancel = new JButton("Cancelar esta nota");
        dialog.getRootPane().setDefaultButton(btnOk);
        btnOk.addActionListener(ev -> {
            continuar[0] = true;
            dialog.dispose();
        });
        btnCancel.addActionListener(ev -> {
            continuar[0] = false;
            dialog.dispose();
        });
        JPanel botoes = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        botoes.add(btnCancel);
        botoes.add(btnOk);
        dialog.add(norte, BorderLayout.NORTH);
        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(botoes, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(920, 440));
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        return continuar[0];
    }

    /**
     * Confere valores do "boleto" (conta a pagar) apos a conferencia dos itens do XML.
     * Permite pagamento a vista ou parcelado (varias linhas em {@code financeiro_lancamentos}).
     */
    private XmlNfeBoletoChoice abrirDialogBoletoXmlNfe(long notaId, org.w3c.dom.Document doc, long fornecedorId,
            String numeroNf, String chave, BigDecimal totalImportado) throws Exception {
        if (!UserPermissions.canAccessFinance(user.role)) {
            int opt = JOptionPane.showConfirmDialog(frame,
                    "Seu perfil nao tem acesso ao Financeiro.\n\n"
                            + "Deseja baixar apenas o estoque, sem criar conta a pagar desta NF?",
                    "Baixa NF-e sem financeiro",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            return opt == JOptionPane.YES_OPTION ? XmlNfeBoletoChoice.semFinanceiro() : XmlNfeBoletoChoice.pular();
        }

        XmlNfeFinanceirosSugeridos sug = sugeridosLancamentoXmlNfe(notaId, doc, fornecedorId, numeroNf, chave, totalImportado);
        String numExibir = numeroNf.isBlank() ? first(doc, "nNF") : numeroNf;
        List<NfeDupXml> dupsXml = lerDuplicatasDocumentoNfe(doc);
        BigDecimal somaItensXml = somaVProdItensNfeXml(doc);
        String vnfXmlRaw = first(doc, "vNF");
        boolean temVnfXml = !vnfXmlRaw.isBlank();
        BigDecimal vnfTotalXml = temVnfXml ? money(vnfXmlRaw).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal diffVnfMenosItens = temVnfXml
                ? vnfTotalXml.subtract(somaItensXml).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        JTextField campoValorTotal = new JTextField(moneyInputText(sug.valor()));
        JTextField campoVenc = new JTextField(sug.vencimento());
        JTextField campoDesc = new JTextField(sug.descricao());
        JTextField campoParceiro = new JTextField(sug.parceiro());
        JTextField campoCategoria = new JTextField(sug.categoria());
        JTextField campoObs = new JTextField(sug.observacao());

        JTextField campoTaxaEmissaoBoleto = new JTextField(moneyInputText(BigDecimal.ZERO));
        campoTaxaEmissaoBoleto.setColumns(8);
        campoTaxaEmissaoBoleto.setToolTipText(
                "Taxa de emissao do boleto por parcela (cada boleto). Pode ser 0 ou ex.: 1,25. "
                        + "Em parcelado, o total vira vNF (ou itens) + taxa x N parcelas; cada linha recebe (valor da NF / N) + taxa.");
        JButton btnUsarVnf = new JButton("Total = vNF");
        JButton btnVnfMaisEmissao = new JButton("Total = vNF + emissao");
        JButton btnUsarItens = new JButton("Total = soma itens");
        JButton btnItensMaisTaxa = new JButton("Total = itens + emissao");
        btnUsarVnf.setToolTipText("Usa apenas o total da NF no XML (vNF), sem taxa de emissao do boleto.");
        btnVnfMaisEmissao.setToolTipText("A vista: vNF + uma taxa. Parcelado: vNF + (taxa x numero de parcelas); cada parcela inclui a taxa no boleto.");
        btnUsarItens.setToolTipText("Usa apenas a soma dos valores de linha (vProd), sem acrescimos finais da NF.");
        btnItensMaisTaxa.setToolTipText("A vista: soma itens + uma taxa. Parcelado: itens + (taxa x parcelas); cada parcela inclui a taxa no boleto.");

        addMoneyFormatOnFocusLost(campoValorTotal);
        addMoneyFormatOnFocusLost(campoTaxaEmissaoBoleto);

        StringBuilder refXmlTxt = new StringBuilder();
        refXmlTxt.append("No XML: soma dos itens (vProd) = ").append(moneyText(somaItensXml));
        if (temVnfXml) {
            refXmlTxt.append(" | vNF (total da NF) = ").append(moneyText(vnfTotalXml));
            refXmlTxt.append(" | diferenca (vNF - itens) = ").append(moneyText(diffVnfMenosItens));
            refXmlTxt.append("\n");
            refXmlTxt.append("No boleto pode aparecer taxa de emissao (as vezes 0, as vezes poucos reais, ex. 1,25).");
            refXmlTxt.append(" Informe esse valor em \"Taxa emissao boleto\" e use \"vNF + emissao\" (ou \"itens + emissao\") para o total a pagar; fica registrado na observacao.");
            refXmlTxt.append(" Em parcelado, essa taxa vale por boleto/parcela: o total vira vNF + (taxa x N) e cada parcela mostra (vNF/N) + taxa.");
        } else {
            refXmlTxt.append("\n(vNF nao encontrado no XML — informe o valor total conforme o boleto ou a nota impressa.)");
            refXmlTxt.append("\nTaxa de emissao do boleto: use o campo \"Taxa emissao boleto\" (pode ser 0); o valor fica na observacao do lancamento.");
        }
        JTextArea taRefXml = new JTextArea(refXmlTxt.toString());
        taRefXml.setEditable(false);
        taRefXml.setOpaque(false);
        taRefXml.setLineWrap(true);
        taRefXml.setWrapStyleWord(true);
        taRefXml.setForeground(TEXT_MUTED);
        taRefXml.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));
        taRefXml.setBorder(new EmptyBorder(2, 0, 2, 0));

        JRadioButton rbAvista = new JRadioButton("A vista (uma parcela)", true);
        JRadioButton rbParcelado = new JRadioButton("Parcelado", false);
        ButtonGroup bgModo = new ButtonGroup();
        bgModo.add(rbAvista);
        bgModo.add(rbParcelado);

        Vector<Integer> opcoesParcelas = new Vector<>();
        for (int p = 2; p <= 48; p++) {
            opcoesParcelas.add(p);
        }
        JComboBox<Integer> cbParcelas = new JComboBox<>(opcoesParcelas);
        cbParcelas.setSelectedItem(2);
        cbParcelas.setPreferredSize(new Dimension(72, scale(28)));

        JButton btnDupXml = new JButton("Preencher do XML (<dup>)");
        btnDupXml.setToolTipText("Usa duplicatas do XML (data e valor de cada parcela), se existirem.");

        DefaultTableModel modelParcelas = new DefaultTableModel(new String[]{"Parc.", "Valor (R$)", "Vencimento (AAAA-MM-DD)"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Integer.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column != 0;
            }
        };
        JTable tabParcelas = new JTable(modelParcelas);
        tabParcelas.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        styleTable(tabParcelas);
        attachParcelasValorFormatoBr(modelParcelas);
        int alturaViewportParcelas = compactMode() ? 200 : 280;
        tabParcelas.setPreferredScrollableViewportSize(new Dimension(680, alturaViewportParcelas));
        JScrollPane scrollParc = new JScrollPane(tabParcelas);
        scrollParc.setPreferredSize(new Dimension(720, alturaViewportParcelas + 28));
        scrollParc.setMinimumSize(new Dimension(560, 140));

        JPanel pAvista = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        pAvista.setOpaque(false);
        pAvista.add(new JLabel("Vencimento (AAAA-MM-DD):"));
        pAvista.add(campoVenc);
        campoVenc.setColumns(14);

        JPanel pParcLinha = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        pParcLinha.setOpaque(false);
        pParcLinha.add(new JLabel("Parcelas:"));
        pParcLinha.add(cbParcelas);
        pParcLinha.add(btnDupXml);

        JPanel pParcelado = new JPanel(new BorderLayout(0, 6));
        pParcelado.setOpaque(false);
        pParcelado.add(pParcLinha, BorderLayout.NORTH);
        pParcelado.add(scrollParc, BorderLayout.CENTER);
        pParcelado.setVisible(false);

        Runnable atualizarLinhasParceladas = () -> {
            if (!rbParcelado.isSelected()) {
                return;
            }
            Integer sel = (Integer) cbParcelas.getSelectedItem();
            int n = sel == null ? 2 : sel;
            BigDecimal total;
            try {
                total = money(campoValorTotal.getText());
            } catch (Exception ex) {
                total = sug.valor();
            }
            if (total.compareTo(BigDecimal.ZERO) <= 0) {
                total = sug.valor();
            }
            total = total.setScale(2, RoundingMode.HALF_UP);
            BigDecimal taxaEm = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            try {
                taxaEm = money(campoTaxaEmissaoBoleto.getText()).setScale(2, RoundingMode.HALF_UP);
            } catch (Exception ignored) {
                // trata como zero
            }
            BigDecimal principalParaRateio = total;
            boolean taxaEmCadaParcela = false;
            if (taxaEm.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal taxaVezesN = taxaEm.multiply(BigDecimal.valueOf(n)).setScale(2, RoundingMode.HALF_UP);
                BigDecimal totalSemTaxasN = total.subtract(taxaVezesN).setScale(2, RoundingMode.HALF_UP);
                BigDecimal totalSemUmaTaxa = total.subtract(taxaEm).setScale(2, RoundingMode.HALF_UP);
                if (temVnfXml && moneyQuaseIgual(totalSemTaxasN, vnfTotalXml)) {
                    principalParaRateio = vnfTotalXml.setScale(2, RoundingMode.HALF_UP);
                    taxaEmCadaParcela = true;
                } else if (moneyQuaseIgual(totalSemTaxasN, somaItensXml.setScale(2, RoundingMode.HALF_UP))) {
                    principalParaRateio = somaItensXml.setScale(2, RoundingMode.HALF_UP);
                    taxaEmCadaParcela = true;
                } else if (temVnfXml && moneyQuaseIgual(totalSemUmaTaxa, vnfTotalXml)) {
                    // Total antigo (vNF + uma taxa): em parcelado passa a ser taxa por boleto.
                    principalParaRateio = vnfTotalXml.setScale(2, RoundingMode.HALF_UP);
                    taxaEmCadaParcela = true;
                    campoValorTotal.setText(moneyInputText(vnfTotalXml.add(taxaVezesN).setScale(2, RoundingMode.HALF_UP)));
                } else if (moneyQuaseIgual(totalSemUmaTaxa, somaItensXml.setScale(2, RoundingMode.HALF_UP))) {
                    principalParaRateio = somaItensXml.setScale(2, RoundingMode.HALF_UP);
                    taxaEmCadaParcela = true;
                    campoValorTotal.setText(moneyInputText(somaItensXml.add(taxaVezesN).setScale(2, RoundingMode.HALF_UP)));
                }
            }
            List<BigDecimal> vals = splitValorEmNParcelas(principalParaRateio, n);
            if (taxaEmCadaParcela) {
                for (int i = 0; i < vals.size(); i++) {
                    vals.set(i, vals.get(i).add(taxaEm).setScale(2, RoundingMode.HALF_UP));
                }
            }
            String v0 = campoVenc.getText().trim();
            if (v0.isBlank()) {
                v0 = sug.vencimento();
            }
            LocalDate base;
            try {
                base = LocalDate.parse(v0);
            } catch (DateTimeParseException ex) {
                base = LocalDate.parse(sug.vencimento());
            }
            modelParcelas.setRowCount(0);
            for (int i = 0; i < n; i++) {
                modelParcelas.addRow(new Object[]{
                        i + 1,
                        moneyInputText(vals.get(i)),
                        base.plusMonths(i).toString()
                });
            }
        };

        rbAvista.addActionListener(ev -> {
            pAvista.setVisible(true);
            pParcelado.setVisible(false);
        });
        rbParcelado.addActionListener(ev -> {
            pAvista.setVisible(false);
            pParcelado.setVisible(true);
            atualizarLinhasParceladas.run();
        });

        final boolean[] ignoraAtualizaParcelas = {false};
        cbParcelas.addActionListener(ev -> {
            if (ignoraAtualizaParcelas[0]) {
                return;
            }
            atualizarLinhasParceladas.run();
        });

        btnDupXml.addActionListener(ev -> {
            try {
                if (dupsXml.isEmpty()) {
                    JOptionPane.showMessageDialog(frame,
                            "Este XML nao possui duplicatas (<dup>) com data de vencimento valida.",
                            "Boleto NF-e",
                            JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                int sz = dupsXml.size();
                if (sz < 2) {
                    JOptionPane.showMessageDialog(frame,
                            "Este XML so tem uma duplicata. Para uma unica parcela use \"A vista\".\n"
                                    + "Parcelado exige pelo menos 2 parcelas (informe manualmente na tabela se precisar).",
                            "Boleto NF-e",
                            JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                sz = Math.min(48, sz);
                ignoraAtualizaParcelas[0] = true;
                try {
                    cbParcelas.setSelectedItem(sz);
                    modelParcelas.setRowCount(0);
                    int k = 1;
                    for (NfeDupXml d : dupsXml) {
                        modelParcelas.addRow(new Object[]{
                                k++,
                                moneyInputText(d.vDup()),
                                d.dVencIso()
                        });
                    }
                } finally {
                    ignoraAtualizaParcelas[0] = false;
                }
                BigDecimal somaDup = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                for (NfeDupXml d : dupsXml) {
                    somaDup = somaDup.add(d.vDup());
                }
                BigDecimal totalInf = money(campoValorTotal.getText()).setScale(2, RoundingMode.HALF_UP);
                BigDecimal taxaDup = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                try {
                    taxaDup = money(campoTaxaEmissaoBoleto.getText()).setScale(2, RoundingMode.HALF_UP);
                } catch (Exception ignored) {
                }
                BigDecimal compararDupCom = totalInf;
                if (taxaDup.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal taxaNx = taxaDup.multiply(BigDecimal.valueOf(sz)).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal tsN = totalInf.subtract(taxaNx).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal ts1 = totalInf.subtract(taxaDup).setScale(2, RoundingMode.HALF_UP);
                    if (temVnfXml && moneyQuaseIgual(tsN, vnfTotalXml)) {
                        compararDupCom = vnfTotalXml.setScale(2, RoundingMode.HALF_UP);
                    } else if (temVnfXml && moneyQuaseIgual(ts1, vnfTotalXml)) {
                        compararDupCom = vnfTotalXml.setScale(2, RoundingMode.HALF_UP);
                    } else if (moneyQuaseIgual(tsN, somaItensXml.setScale(2, RoundingMode.HALF_UP))) {
                        compararDupCom = somaItensXml.setScale(2, RoundingMode.HALF_UP);
                    } else if (moneyQuaseIgual(ts1, somaItensXml.setScale(2, RoundingMode.HALF_UP))) {
                        compararDupCom = somaItensXml.setScale(2, RoundingMode.HALF_UP);
                    }
                }
                if (somaDup.compareTo(BigDecimal.ZERO) > 0
                        && somaDup.subtract(compararDupCom).abs().compareTo(new BigDecimal("0.02")) > 0) {
                    JOptionPane.showMessageDialog(frame,
                            "A soma das duplicatas no XML (" + moneyText(somaDup) + ") difere do valor da NF sem taxa de emissao ("
                                    + moneyText(compararDupCom) + ") ou do valor total informado ("
                                    + moneyText(totalInf) + "). Ajuste os valores na tabela ou o valor total.",
                            "Boleto NF-e",
                            JOptionPane.WARNING_MESSAGE);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, friendlyMessage(ex), "Boleto NF-e", JOptionPane.WARNING_MESSAGE);
            }
        });

        btnUsarVnf.addActionListener(ev -> {
            if (!temVnfXml) {
                JOptionPane.showMessageDialog(frame, "Este XML nao tem vNF para copiar.", "Boleto NF-e", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            campoValorTotal.setText(moneyInputText(vnfTotalXml));
            if (rbParcelado.isSelected()) {
                atualizarLinhasParceladas.run();
            }
        });
        btnVnfMaisEmissao.addActionListener(ev -> {
            try {
                if (!temVnfXml) {
                    JOptionPane.showMessageDialog(frame, "Este XML nao tem vNF.", "Boleto NF-e", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                BigDecimal em = money(campoTaxaEmissaoBoleto.getText());
                BusinessRules.requireNonNegative(em, "Taxa de emissao do boleto");
                int nPar = rbParcelado.isSelected()
                        ? (cbParcelas.getSelectedItem() instanceof Integer i ? i : 2)
                        : 1;
                BigDecimal novo = vnfTotalXml.add(em.multiply(BigDecimal.valueOf(nPar))).setScale(2, RoundingMode.HALF_UP);
                campoValorTotal.setText(moneyInputText(novo));
                if (rbParcelado.isSelected()) {
                    atualizarLinhasParceladas.run();
                }
            } catch (AppException ex) {
                JOptionPane.showMessageDialog(frame, ex.getMessage(), "Boleto NF-e", JOptionPane.WARNING_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, friendlyMessage(ex), "Boleto NF-e", JOptionPane.WARNING_MESSAGE);
            }
        });
        btnUsarItens.addActionListener(ev -> {
            campoValorTotal.setText(moneyInputText(somaItensXml));
            if (rbParcelado.isSelected()) {
                atualizarLinhasParceladas.run();
            }
        });
        btnItensMaisTaxa.addActionListener(ev -> {
            try {
                BigDecimal em = money(campoTaxaEmissaoBoleto.getText());
                BusinessRules.requireNonNegative(em, "Taxa de emissao do boleto");
                int nPar = rbParcelado.isSelected()
                        ? (cbParcelas.getSelectedItem() instanceof Integer i ? i : 2)
                        : 1;
                BigDecimal novo = somaItensXml.add(em.multiply(BigDecimal.valueOf(nPar))).setScale(2, RoundingMode.HALF_UP);
                campoValorTotal.setText(moneyInputText(novo));
                if (rbParcelado.isSelected()) {
                    atualizarLinhasParceladas.run();
                }
            } catch (AppException ex) {
                JOptionPane.showMessageDialog(frame, ex.getMessage(), "Boleto NF-e", JOptionPane.WARNING_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, friendlyMessage(ex), "Boleto NF-e", JOptionPane.WARNING_MESSAGE);
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        int y = 0;
        gc.gridx = 0;
        gc.gridy = y;
        form.add(new JLabel("Valor total (R$)"), gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        form.add(campoValorTotal, gc);
        gc.gridx = 0;
        gc.gridy = ++y;
        gc.gridwidth = 2;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        form.add(taRefXml, gc);
        gc.gridy = ++y;
        JPanel refBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        refBtns.setOpaque(false);
        refBtns.add(btnUsarVnf);
        refBtns.add(btnVnfMaisEmissao);
        refBtns.add(btnUsarItens);
        refBtns.add(new JLabel("Taxa emissao boleto R$:"));
        refBtns.add(campoTaxaEmissaoBoleto);
        refBtns.add(btnItensMaisTaxa);
        form.add(refBtns, gc);
        gc.gridwidth = 1;
        gc.gridx = 0;
        gc.gridy = ++y;
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;
        JPanel modoPag = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        modoPag.setOpaque(false);
        modoPag.add(rbAvista);
        modoPag.add(rbParcelado);
        form.add(modoPag, gc);
        gc.gridwidth = 2;
        gc.gridx = 0;
        gc.gridy = ++y;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        form.add(pAvista, gc);
        gc.gridy = ++y;
        gc.fill = GridBagConstraints.BOTH;
        gc.weightx = 1.0;
        gc.weighty = 0.35;
        form.add(pParcelado, gc);
        gc.weighty = 0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridwidth = 1;
        gc.gridx = 0;
        gc.gridy = ++y;
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;
        form.add(new JLabel("Descricao"), gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        form.add(campoDesc, gc);
        gc.gridx = 0;
        gc.gridy = ++y;
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;
        form.add(new JLabel("Parceiro"), gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        form.add(campoParceiro, gc);
        gc.gridx = 0;
        gc.gridy = ++y;
        form.add(new JLabel("Categoria"), gc);
        gc.gridx = 1;
        form.add(campoCategoria, gc);
        gc.gridx = 0;
        gc.gridy = ++y;
        form.add(new JLabel("Observacao"), gc);
        gc.gridx = 1;
        form.add(campoObs, gc);

        JPanel norte = new JPanel();
        norte.setLayout(new BoxLayout(norte, BoxLayout.Y_AXIS));
        norte.setOpaque(false);
        norte.add(new JLabel("Conta a pagar (boleto) desta NF-e apos conferir os itens do XML."));
        norte.add(Box.createVerticalStrut(6));
        norte.add(new JLabel("Nota fiscal #" + notaId + (numExibir.isBlank() ? "" : " | NF " + numExibir)));

        final XmlNfeBoletoChoice[] resultado = {XmlNfeBoletoChoice.pular()};
        JDialog dialog = new JDialog(frame, "Boleto / financeiro da NF-e", true);
        dialog.setLayout(new BorderLayout(10, 10));
        ((JPanel) dialog.getContentPane()).setBorder(new EmptyBorder(10, 12, 10, 12));
        JButton ok = new JButton("Confirmar baixa e registrar");
        JButton cancel = new JButton("Cancelar");
        dialog.getRootPane().setDefaultButton(ok);
        ok.addActionListener(ev -> {
            try {
                requireFinanceAccess();
                BigDecimal total = money(campoValorTotal.getText());
                BusinessRules.requirePositive(total, "Valor total");
                BusinessRules.requireNotBlank(campoDesc.getText(), "Descricao");
                String descBase = campoDesc.getText().trim();
                String parc = campoParceiro.getText().trim();
                String cat = campoCategoria.getText().trim();
                String obsBase = campoObs.getText().trim();
                BigDecimal taxaEmissaoBoleto = money(campoTaxaEmissaoBoleto.getText());
                BusinessRules.requireNonNegative(taxaEmissaoBoleto, "Taxa de emissao do boleto");
                List<String> obsPartes = new ArrayList<>();
                if (!obsBase.isBlank()) {
                    obsPartes.add(obsBase.trim());
                }
                if (temVnfXml) {
                    obsPartes.add("Ref. vNF XML: " + moneyText(vnfTotalXml));
                }
                if (rbParcelado.isSelected() && taxaEmissaoBoleto.compareTo(BigDecimal.ZERO) > 0) {
                    int nObs = modelParcelas.getRowCount();
                    BigDecimal taxaLinhas = taxaEmissaoBoleto.multiply(BigDecimal.valueOf(nObs)).setScale(2, RoundingMode.HALF_UP);
                    obsPartes.add("Taxa emissao boleto (por parcela): " + moneyText(taxaEmissaoBoleto) + " x " + nObs + " = " + moneyText(taxaLinhas));
                } else {
                    obsPartes.add("Taxa emissao boleto: " + moneyText(taxaEmissaoBoleto));
                }
                String obsFin = String.join(" | ", obsPartes);

                if (rbAvista.isSelected()) {
                    String venc = campoVenc.getText().trim();
                    BusinessRules.requireNotBlank(venc, "Vencimento");
                    LocalDate.parse(venc);
                    resultado[0] = XmlNfeBoletoChoice.comLancamento(new DesktopFinanceService.FinanceEntryRequest(
                            "PAGAR",
                            descBase,
                            parc,
                            cat,
                            total,
                            venc,
                            obsFin,
                            notaId
                    ));
                } else {
                    int n = modelParcelas.getRowCount();
                    if (n < 2) {
                        throw new AppException("Informe pelo menos 2 parcelas ou escolha \"A vista\".");
                    }
                    List<DesktopFinanceService.FinanceEntryRequest> lista = new ArrayList<>();
                    BigDecimal soma = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                    for (int i = 0; i < n; i++) {
                        Object vo = modelParcelas.getValueAt(i, 1);
                        Object to = modelParcelas.getValueAt(i, 2);
                        String vs = vo == null ? "" : vo.toString().trim();
                        String ds = to == null ? "" : to.toString().trim();
                        BusinessRules.requireNotBlank(ds, "Vencimento da parcela " + (i + 1));
                        LocalDate.parse(ds);
                        BigDecimal vi = money(vs);
                        BusinessRules.requirePositive(vi, "Valor da parcela " + (i + 1));
                        soma = soma.add(vi);
                        String descP = descBase + " (" + (i + 1) + "/" + n + ")";
                        String obsP = obsFin.isBlank()
                                ? ("Parcela " + (i + 1) + "/" + n)
                                : (obsFin + " | parcela " + (i + 1) + "/" + n);
                        lista.add(new DesktopFinanceService.FinanceEntryRequest(
                                "PAGAR",
                                descP,
                                parc,
                                cat,
                                vi,
                                ds,
                                obsP,
                                notaId
                        ));
                    }
                    soma = soma.setScale(2, RoundingMode.HALF_UP);
                    BigDecimal totSc = total.setScale(2, RoundingMode.HALF_UP);
                    if (soma.compareTo(totSc) != 0) {
                        throw new AppException("A soma das parcelas (" + moneyText(soma)
                                + ") deve ser igual ao valor total (" + moneyText(totSc) + ").");
                    }
                    resultado[0] = XmlNfeBoletoChoice.comLancamentos(lista);
                }
                DESKTOP_PREFS.putBoolean(PREF_XML_NFE_BOLETO_PARCELADO, rbParcelado.isSelected());
                if (rbParcelado.isSelected()) {
                    Integer sel = (Integer) cbParcelas.getSelectedItem();
                    if (sel != null) {
                        DESKTOP_PREFS.putInt(PREF_XML_NFE_BOLETO_N_PARCELAS, sel);
                    }
                }
                dialog.dispose();
            } catch (AppException ex) {
                JOptionPane.showMessageDialog(dialog, ex.getMessage(), "Boleto NF-e", JOptionPane.WARNING_MESSAGE);
            } catch (DateTimeParseException ex) {
                JOptionPane.showMessageDialog(dialog, friendlyMessage(ex), "Boleto NF-e", JOptionPane.WARNING_MESSAGE);
            } catch (Exception ex) {
                error(ex);
            }
        });
        cancel.addActionListener(ev -> {
            resultado[0] = XmlNfeBoletoChoice.pular();
            dialog.dispose();
        });
        JPanel botoes = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        botoes.add(cancel);
        botoes.add(ok);
        dialog.add(norte, BorderLayout.NORTH);
        dialog.add(form, BorderLayout.CENTER);
        dialog.add(botoes, BorderLayout.SOUTH);

        boolean prefParc = DESKTOP_PREFS.getBoolean(PREF_XML_NFE_BOLETO_PARCELADO, true);
        int prefN = DESKTOP_PREFS.getInt(PREF_XML_NFE_BOLETO_N_PARCELAS, 2);
        prefN = Math.min(48, Math.max(2, prefN));
        cbParcelas.setSelectedItem(prefN);
        if (prefParc) {
            rbParcelado.setSelected(true);
            pAvista.setVisible(false);
            pParcelado.setVisible(true);
            atualizarLinhasParceladas.run();
        } else {
            rbAvista.setSelected(true);
            pAvista.setVisible(true);
            pParcelado.setVisible(false);
        }

        dialog.pack();
        dialog.setMinimumSize(new Dimension(840, compactMode() ? 520 : 580));
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        return resultado[0];
    }

    /** Processa uma nota PENDENTE: entrada no estoque, status BAIXADO e opcional lancamento(s) financeiro(s) (mesma transacao). */
    private void processarBaixaXmlNfe(long notaId, org.w3c.dom.Document doc,
            List<DesktopFinanceService.FinanceEntryRequest> lancamentosFinanceiro) throws Exception {
        withTransaction(() -> {
            Map<String, Object> row = one("select id, fornecedor_id, xml_path from notas_fiscais where id=? and status='PENDENTE'", notaId);
            if (row == null || row.isEmpty()) {
                throw new AppException("Nota fiscal nao encontrada ou ja baixada.");
            }
            String pathStr = Objects.toString(row.get("xml_path"), "");
            if (pathStr.isBlank()) {
                throw new AppException("XML sem caminho de arquivo.");
            }
            File file = new File(pathStr);
            if (!file.exists()) {
                throw new AppException("Arquivo XML nao encontrado: " + file.getName());
            }
            long fornecedorId = ((Number) row.get("fornecedor_id")).longValue();
            int itens = aplicarItensNfeNoEstoque(doc, fornecedorId, notaId);
            update("update notas_fiscais set status='BAIXADO' where id=?", notaId);
            if (lancamentosFinanceiro != null && !lancamentosFinanceiro.isEmpty()) {
                Map<String, Object> dup = one("select id from financeiro_lancamentos where nota_fiscal_id=? limit 1", notaId);
                if (dup == null) {
                    for (DesktopFinanceService.FinanceEntryRequest req : lancamentosFinanceiro) {
                        financeService.createEntry(req, user.id);
                    }
                }
            }
            audit("XML_NFE_BAIXA", "nota_id=" + notaId + " itens=" + itens + " lanc_fin=" + (
                    lancamentosFinanceiro == null ? 0 : lancamentosFinanceiro.size()));
            return itens;
        });
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
        return defaultLocalSqliteJdbcUrl();
    }

    /**
     * Banco local padrao {@code data/mercado-tonico.db}. Se ainda existir apenas
     * o arquivo antigo {@code mercado-tunico.db}, usa ele para nao perder dados.
     */
    private static String defaultLocalSqliteJdbcUrl() {
        try {
            if (Files.exists(SQLITE_DB_FILE) || !Files.exists(SQLITE_DB_FILE_LEGACY)) {
                return "jdbc:sqlite:data/mercado-tonico.db";
            }
        } catch (Exception ignored) {
            // ignora e cai no legado
        }
        return "jdbc:sqlite:data/mercado-tunico.db";
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
            JPanel auth = new JPanel(new GridLayout(0, 1, 8, 8));
            auth.add(new JLabel("Cliente: " + nomeCliente));
            auth.add(new JLabel("<html>Para confirmar a <b>exclus\u00e3o</b> (remo\u00e7\u00e3o da lista de conv\u00eanio), "
                    + "digite a senha de <b>autoriza\u00e7\u00e3o</b> do estabelecimento "
                    + "ou a senha de login do <b>Admin</b> / <b>Gerente</b>:</html>"));
            JPasswordField pf = new JPasswordField(18);
            pf.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(14)));
            auth.add(pf);
            int op = JOptionPane.showConfirmDialog(frame, auth, "Excluir cliente",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (op != JOptionPane.OK_OPTION) {
                return;
            }
            if (!adminSenhaConfere(pf.getPassword())) {
                msg("Senha de administrador invalida. O cliente nao foi removido.");
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
     * Data da venda no padrao {@code dd/MM/yyyy} para listagens do convenio.
     */
    private static String formatarDataVendaConvenio(Object timestamp) {
        if (timestamp == null) {
            return "-";
        }
        String s = String.valueOf(timestamp).trim();
        if (s.isEmpty()) {
            return "-";
        }
        try {
            if (s.length() >= 10 && s.charAt(4) == '-' && s.charAt(7) == '-') {
                return LocalDate.parse(s.substring(0, 10)).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            }
        } catch (Exception ignored) {
            // segue para LocalDateTime
        }
        try {
            String norm = s.contains(" ") && !s.contains("T") ? s.replace(' ', 'T') : s;
            int dot = norm.indexOf('.');
            if (dot > 0) {
                norm = norm.substring(0, dot);
            }
            return LocalDateTime.parse(norm).toLocalDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (Exception e) {
            return s.length() > 10 ? s.substring(0, 10) : s;
        }
    }

    /**
     * Lista produtos das vendas no convênio ainda em aberto para o cliente
     * selecionado na tabela "Clientes" (aba Convênio).
     */
    private void mostrarItensConvenioEmAberto(JTable tabelaClientes) {
        try {
            requireFiadoAccess();
            int viewRow = tabelaClientes.getSelectedRow();
            if (viewRow < 0) {
                msg("Selecione um cliente na tabela \"Clientes\".");
                return;
            }
            int modelRow = tabelaClientes.convertRowIndexToModel(viewRow);
            DefaultTableModel tm = (DefaultTableModel) tabelaClientes.getModel();
            long clienteId = ((Number) tm.getValueAt(modelRow, 0)).longValue();
            String nomeCliente = String.valueOf(tm.getValueAt(modelRow, 1));

            List<Map<String, Object>> produtos = rows("""
                    select f.id as fiado_id,
                           v.timestamp as data_venda,
                           p.nome as produto,
                           vi.quantidade as qtd,
                           vi.preco_unitario as preco_unit,
                           (vi.quantidade * vi.preco_unitario) as subtotal
                      from fiado f
                      join vendas v on v.id = f.venda_id
                      join venda_itens vi on vi.venda_id = v.id
                      join produtos p on p.id = vi.produto_id
                     where f.cliente_id = ? and f.status = 'ABERTO'
                     order by f.data_criacao asc, f.id asc, vi.id asc
                    """, clienteId);

            List<Map<String, Object>> semVenda = rows("""
                    select id, valor, valor_pago
                      from fiado
                     where cliente_id = ? and status = 'ABERTO' and venda_id is null
                     order by data_criacao asc, id asc
                    """, clienteId);

            JDialog d = new JDialog(frame, "Itens em aberto — " + nomeCliente, true);
            d.setLayout(new BorderLayout(0, 0));

            JPanel north = new JPanel(new BorderLayout());
            north.setBorder(new EmptyBorder(10, 12, 6, 12));
            JLabel hdr = new JLabel(nomeCliente + "  (#" + clienteId + ")");
            hdr.setFont(new Font("Segoe UI", Font.BOLD, fontSize(14)));
            north.add(hdr, BorderLayout.CENTER);
            d.add(north, BorderLayout.NORTH);

            JPanel centerWrap = new JPanel(new BorderLayout());
            if (!produtos.isEmpty()) {
                DefaultTableModel model = new DefaultTableModel(
                        new String[]{"Fiado", "Data de venda", "Produto", "Quantidade", "Unit.", "Subtotal"}, 0) {
                    @Override
                    public boolean isCellEditable(int row, int column) {
                        return false;
                    }
                };
                for (Map<String, Object> row : produtos) {
                    model.addRow(new Object[]{
                            row.get("fiado_id"),
                            formatarDataVendaConvenio(row.get("data_venda")),
                            row.get("produto"),
                            row.get("qtd"),
                            moneyText(money(String.valueOf(row.get("preco_unit")))),
                            moneyText(money(String.valueOf(row.get("subtotal"))))
                    });
                }
                JTable t = new JTable(model);
                t.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));
                t.setRowHeight(t.getRowHeight() + 4);
                t.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
                if (t.getColumnCount() >= 6) {
                    t.getColumnModel().getColumn(0).setPreferredWidth(52);
                    t.getColumnModel().getColumn(1).setPreferredWidth(100);
                    t.getColumnModel().getColumn(2).setPreferredWidth(380);
                    t.getColumnModel().getColumn(3).setPreferredWidth(72);
                    t.getColumnModel().getColumn(4).setPreferredWidth(88);
                    t.getColumnModel().getColumn(5).setPreferredWidth(96);
                }
                centerWrap.add(new JScrollPane(t), BorderLayout.CENTER);
            } else {
                centerWrap.add(new JLabel(
                        "<html><i>Nenhum item de venda no convênio em aberto.</i></html>",
                        SwingConstants.CENTER), BorderLayout.CENTER);
            }

            if (!semVenda.isEmpty()) {
                StringBuilder sb = new StringBuilder(
                        "<html><b>Lançamentos sem venda vinculada</b> (só total em convênio):<br>");
                for (Map<String, Object> sv : semVenda) {
                    BigDecimal ab = money(String.valueOf(sv.get("valor")))
                            .subtract(money(String.valueOf(sv.get("valor_pago"))));
                    sb.append("Fiado #").append(sv.get("id")).append(": ")
                            .append(moneyText(ab)).append(" em aberto<br>");
                }
                sb.append("</html>");
                JLabel extra = new JLabel(sb.toString());
                extra.setBorder(new EmptyBorder(8, 12, 4, 12));
                centerWrap.add(extra, BorderLayout.SOUTH);
            }

            d.add(centerWrap, BorderLayout.CENTER);

            JButton fechar = new JButton("Fechar");
            fechar.addActionListener(ev -> d.dispose());
            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            south.setBorder(new EmptyBorder(4, 8, 10, 8));
            south.add(fechar);
            d.add(south, BorderLayout.SOUTH);

            int h = Math.min(520, uiReferenceSize.height - 120);
            d.setSize(920, h);
            d.setLocationRelativeTo(frame);
            d.setVisible(true);
        } catch (Exception ex) {
            error(ex);
        }
    }

    /**
     * Abre o fluxo de "Dar baixa no convenio".
     *
     * <p>Etapa 1: pequeno dialogo com BUSCA INTELIGENTE (autocomplete).
     * Operador digita parte do nome ou CPF; aparece a lista filtrada
     * em tempo real, com o saldo aberto de cada cliente. Setas + Enter
     * ou clique para confirmar.</p>
     *
     * <p>Etapa 2: dialogo com tabela de lançamentos em aberto: marque quais
     * entram na baixa (atalhos F5–F8), ajuste o valor e a forma de pagamento.
     * A distribuição é FIFO apenas entre os lançamentos marcados.</p>
     */
    private void darBaixaConvenio() {
        try {
            requireFiadoAccess();
            long[] selecionado = selecionarClienteParaBaixaConvenio();
            if (selecionado == null) {
                return;
            }
            long clienteId = selecionado[0];
            Map<String, Object> info = one(
                    "select nome from clientes where id = ?", clienteId);
            String nomeCliente = info == null ? ("#" + clienteId)
                    : String.valueOf(info.get("nome"));

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

            List<Map<String, Object>> abertosLista = rows("""
                    select f.id, f.venda_id, f.valor, f.valor_pago, f.data_criacao,
                           (f.valor - f.valor_pago) as aberto
                      from fiado f
                     where f.cliente_id = ? and f.status = 'ABERTO'
                     order by f.data_criacao asc, f.id asc
                    """, clienteId);

            Map<Long, BigDecimal> abertoPorFiadoId = new LinkedHashMap<>();
            for (Map<String, Object> linhaFi : abertosLista) {
                long fid0 = ((Number) linhaFi.get("id")).longValue();
                abertoPorFiadoId.put(fid0, money(String.valueOf(linhaFi.get("aberto"))));
            }

            List<Map<String, Object>> linhasItens = rows("""
                    select f.id as fiado_id,
                           v.timestamp as data_venda,
                           p.nome as produto,
                           vi.quantidade as qtd,
                           vi.preco_unitario as preco_unit,
                           (vi.quantidade * vi.preco_unitario) as subtotal
                      from fiado f
                      join vendas v on v.id = f.venda_id
                      join venda_itens vi on vi.venda_id = v.id
                      join produtos p on p.id = vi.produto_id
                     where f.cliente_id = ? and f.status = 'ABERTO'
                     order by f.data_criacao asc, f.id asc, vi.id asc
                    """, clienteId);

            Set<Long> fiadosComLinhaProduto = new HashSet<>();
            for (Map<String, Object> ri : linhasItens) {
                fiadosComLinhaProduto.add(((Number) ri.get("fiado_id")).longValue());
            }

            final boolean[] syncingChecks = {false};

            DefaultTableModel tmodel = new DefaultTableModel(
                    new Object[]{"Pagar", "Fiado", "Data de venda", "Produto", "Quantidade", "Unit.", "Subtotal"}, 0) {
                @Override
                public Class<?> getColumnClass(int columnIndex) {
                    return switch (columnIndex) {
                        case 0 -> Boolean.class;
                        case 1 -> Long.class;
                        case 4 -> Object.class;
                        default -> String.class;
                    };
                }

                @Override
                public boolean isCellEditable(int row, int column) {
                    return column == 0;
                }
            };

            for (Map<String, Object> row : linhasItens) {
                tmodel.addRow(new Object[]{
                        Boolean.TRUE,
                        ((Number) row.get("fiado_id")).longValue(),
                        formatarDataVendaConvenio(row.get("data_venda")),
                        row.get("produto"),
                        row.get("qtd"),
                        moneyText(money(String.valueOf(row.get("preco_unit")))),
                        moneyText(money(String.valueOf(row.get("subtotal"))))
                });
            }
            for (Map<String, Object> linhaFi : abertosLista) {
                long fid = ((Number) linhaFi.get("id")).longValue();
                if (!fiadosComLinhaProduto.contains(fid)) {
                    BigDecimal ab = money(String.valueOf(linhaFi.get("aberto")));
                    String detalhe = linhaFi.get("venda_id") == null
                            ? "(Convênio sem venda vinculada — valor total do lançamento)"
                            : "(Sem itens listados — valor total do lançamento)";
                    tmodel.addRow(new Object[]{
                            Boolean.TRUE,
                            fid,
                            formatarDataVendaConvenio(linhaFi.get("data_criacao")),
                            detalhe,
                            "-",
                            "-",
                            moneyText(ab)
                    });
                }
            }

            JTextField campoValor = new JTextField(moneyInputText(saldoAberto));
            stylePdvField(campoValor);
            campoValor.setColumns(14);
            bindMoneyMaskLive(campoValor);
            JComboBox<String> formaPg = new JComboBox<>(
                    new String[]{"DINHEIRO", "PIX", "DEBITO", "CREDITO"});

            JPanel painelDinheiroRecebido = new JPanel(new GridBagLayout());
            painelDinheiroRecebido.setOpaque(false);
            painelDinheiroRecebido.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_SOFT),
                    new EmptyBorder(10, 0, 0, 0)));
            JLabel lblRecebido = label("Valor recebido (cliente)");
            JTextField campoRecebidoDinheiro = new JTextField(moneyInputText(BigDecimal.ZERO));
            stylePdvField(campoRecebidoDinheiro);
            campoRecebidoDinheiro.setColumns(16);
            bindMoneyMaskLive(campoRecebidoDinheiro);
            JLabel lblTrocoBaixa = new JLabel(" ");
            lblTrocoBaixa.setFont(new Font("Segoe UI", Font.BOLD, fontSize(13)));
            GridBagConstraints gbd = new GridBagConstraints();
            gbd.insets = new Insets(4, 0, 4, 8);
            gbd.anchor = GridBagConstraints.WEST;
            gbd.gridx = 0;
            gbd.gridy = 0;
            painelDinheiroRecebido.add(lblRecebido, gbd);
            gbd.gridx = 1;
            gbd.fill = GridBagConstraints.HORIZONTAL;
            gbd.weightx = 1;
            painelDinheiroRecebido.add(campoRecebidoDinheiro, gbd);
            gbd.gridx = 0;
            gbd.gridy = 1;
            gbd.gridwidth = 2;
            gbd.fill = GridBagConstraints.HORIZONTAL;
            painelDinheiroRecebido.add(lblTrocoBaixa, gbd);

            final Runnable[] atualizarTrocoBaixa = new Runnable[1];
            atualizarTrocoBaixa[0] = () -> {
                if (!"DINHEIRO".equals(String.valueOf(formaPg.getSelectedItem()))) {
                    lblTrocoBaixa.setText(" ");
                    return;
                }
                BigDecimal valPagar;
                try {
                    valPagar = money(campoValor.getText());
                } catch (Exception ex) {
                    lblTrocoBaixa.setForeground(MARKET_ORANGE);
                    lblTrocoBaixa.setText("Confira o valor a pagar.");
                    return;
                }
                BigDecimal recebido;
                try {
                    recebido = money(campoRecebidoDinheiro.getText());
                } catch (Exception ex) {
                    lblTrocoBaixa.setForeground(MARKET_ORANGE);
                    lblTrocoBaixa.setText("Valor recebido invalido.");
                    return;
                }
                if (recebido.compareTo(valPagar) < 0) {
                    lblTrocoBaixa.setForeground(MARKET_ORANGE);
                    lblTrocoBaixa.setText("Faltam " + moneyText(valPagar.subtract(recebido))
                            + " (recebido menor que o valor a pagar).");
                } else {
                    lblTrocoBaixa.setForeground(MARKET_GREEN);
                    lblTrocoBaixa.setText("Troco a devolver ao cliente: " + moneyText(recebido.subtract(valPagar)));
                }
            };

            DocumentListener trocoDoc = new DocumentListener() {
                private void tick() {
                    SwingUtilities.invokeLater(atualizarTrocoBaixa[0]);
                }
                @Override public void insertUpdate(DocumentEvent e) { tick(); }
                @Override public void removeUpdate(DocumentEvent e) { tick(); }
                @Override public void changedUpdate(DocumentEvent e) { tick(); }
            };
            campoRecebidoDinheiro.getDocument().addDocumentListener(trocoDoc);
            campoValor.getDocument().addDocumentListener(trocoDoc);

            Runnable syncValorFromSelection = () -> {
                Set<Long> fiadosMarcados = new LinkedHashSet<>();
                for (int r = 0; r < tmodel.getRowCount(); r++) {
                    if (Boolean.TRUE.equals(tmodel.getValueAt(r, 0))) {
                        fiadosMarcados.add(((Number) tmodel.getValueAt(r, 1)).longValue());
                    }
                }
                BigDecimal sum = BigDecimal.ZERO;
                for (Long fid : fiadosMarcados) {
                    sum = sum.add(abertoPorFiadoId.getOrDefault(fid, BigDecimal.ZERO));
                }
                campoValor.setText(moneyInputText(sum));
                atualizarTrocoBaixa[0].run();
            };

            tmodel.addTableModelListener(e -> {
                if (syncingChecks[0]) {
                    return;
                }
                if (e.getColumn() != TableModelEvent.ALL_COLUMNS && e.getColumn() != 0) {
                    return;
                }
                int row = e.getFirstRow();
                if (row < 0 || row >= tmodel.getRowCount()) {
                    return;
                }
                long fid = ((Number) tmodel.getValueAt(row, 1)).longValue();
                boolean marcar = Boolean.TRUE.equals(tmodel.getValueAt(row, 0));
                syncingChecks[0] = true;
                try {
                    for (int rr = 0; rr < tmodel.getRowCount(); rr++) {
                        if (((Number) tmodel.getValueAt(rr, 1)).longValue() != fid) {
                            continue;
                        }
                        Object atual = tmodel.getValueAt(rr, 0);
                        if (!Boolean.valueOf(marcar).equals(atual)) {
                            tmodel.setValueAt(marcar, rr, 0);
                        }
                    }
                } finally {
                    syncingChecks[0] = false;
                }
                syncValorFromSelection.run();
            });

            JTable tabelaLanc = new JTable(tmodel);
            tabelaLanc.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(12)));
            tabelaLanc.setRowHeight(tabelaLanc.getRowHeight() + 4);
            tabelaLanc.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            tabelaLanc.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            if (tabelaLanc.getColumnCount() >= 7) {
                tabelaLanc.getColumnModel().getColumn(0).setPreferredWidth(52);
                tabelaLanc.getColumnModel().getColumn(1).setPreferredWidth(52);
                tabelaLanc.getColumnModel().getColumn(2).setPreferredWidth(100);
                tabelaLanc.getColumnModel().getColumn(3).setPreferredWidth(300);
                tabelaLanc.getColumnModel().getColumn(4).setPreferredWidth(72);
                tabelaLanc.getColumnModel().getColumn(5).setPreferredWidth(88);
                tabelaLanc.getColumnModel().getColumn(6).setPreferredWidth(96);
            }

            JLabel lblNome = new JLabel(nomeCliente + "  (#" + clienteId + ")");
            lblNome.setFont(new Font("Segoe UI", Font.BOLD, fontSize(13)));
            JLabel lblSaldo = new JLabel(moneyText(saldoAberto)
                    + "   (" + qtdLancamentos + " lan\u00e7amento"
                    + (qtdLancamentos == 1 ? "" : "s") + ")");
            lblSaldo.setForeground(new Color(0xC6, 0x28, 0x28));
            lblSaldo.setFont(new Font("Segoe UI", Font.BOLD, fontSize(13)));

            JLabel ajuda = new JLabel(
                    "<html><i>Cada linha e um item da venda; marcar qualquer linha de um <b>Fiado</b> "
                            + "marca ou desmarca o lançamento inteiro (mesmo número de Fiado). "
                            + "O valor acompanha a soma dos lançamentos marcados (pode reduzir para parcial). "
                            + "FIFO só entre os lançamentos marcados.<br>"
                            + "<b>F5</b> marca o lançamento da linha selecionada &nbsp; <b>F6</b> desmarca &nbsp; "
                            + "<b>F7</b> marca todos &nbsp; <b>F8</b> desmarca todos.</i></html>");
            ajuda.setFont(new Font("Segoe UI", Font.PLAIN, fontSize(11)));
            ajuda.setForeground(new Color(0x5D, 0x40, 0x37));

            JPanel topo = new JPanel(new GridLayout(0, 2, 8, 6));
            topo.add(label("Cliente"));
            topo.add(lblNome);
            topo.add(label("Saldo em aberto"));
            topo.add(lblSaldo);

            JPanel rodapeForm = new JPanel();
            rodapeForm.setLayout(new BoxLayout(rodapeForm, BoxLayout.Y_AXIS));
            rodapeForm.setOpaque(false);
            JPanel linhaValorPagar = new JPanel(new GridLayout(1, 2, 8, 6));
            linhaValorPagar.setOpaque(false);
            linhaValorPagar.add(label("Valor a pagar"));
            linhaValorPagar.add(campoValor);
            JPanel linhaForma = new JPanel(new GridLayout(1, 2, 8, 6));
            linhaForma.setOpaque(false);
            linhaForma.add(label("Forma de pagamento"));
            linhaForma.add(formaPg);
            rodapeForm.add(linhaValorPagar);
            rodapeForm.add(Box.createVerticalStrut(6));
            rodapeForm.add(linhaForma);
            rodapeForm.add(Box.createVerticalStrut(8));
            rodapeForm.add(painelDinheiroRecebido);
            painelDinheiroRecebido.setAlignmentX(Component.LEFT_ALIGNMENT);
            formaPg.addActionListener(ev -> {
                boolean din = "DINHEIRO".equals(String.valueOf(formaPg.getSelectedItem()));
                painelDinheiroRecebido.setVisible(din);
                if (din) {
                    campoRecebidoDinheiro.setText(moneyInputText(BigDecimal.ZERO));
                }
                atualizarTrocoBaixa[0].run();
                SwingUtilities.invokeLater(() -> rodapeForm.revalidate());
            });
            painelDinheiroRecebido.setVisible("DINHEIRO".equals(String.valueOf(formaPg.getSelectedItem())));

            JPanel norte = new JPanel(new BorderLayout(0, 6));
            norte.setBorder(new EmptyBorder(0, 0, 8, 0));
            norte.add(topo, BorderLayout.NORTH);
            norte.add(ajuda, BorderLayout.SOUTH);

            JPanel centro = new JPanel(new BorderLayout(0, 6));
            centro.add(new JLabel("Itens em aberto (marque os lan\u00e7amentos que entram na baixa):"),
                    BorderLayout.NORTH);
            centro.add(new JScrollPane(tabelaLanc), BorderLayout.CENTER);

            final BigDecimal[] valorConfirmado = {null};
            final String[] formaConfirmada = {null};

            JDialog dialog = new JDialog(frame, "\uD83D\uDCB5  Dar baixa no conv\u00eanio", true);
            dialog.setLayout(new BorderLayout(10, 10));
            ((JPanel) dialog.getContentPane()).setBorder(new EmptyBorder(10, 12, 10, 12));

            JButton ok = new JButton("OK");
            JButton cancel = new JButton("Cancelar");
            dialog.getRootPane().setDefaultButton(ok);

            ok.addActionListener(ev -> {
                try {
                    Set<Long> fiadosMarcadosOk = new LinkedHashSet<>();
                    for (int r = 0; r < tmodel.getRowCount(); r++) {
                        if (Boolean.TRUE.equals(tmodel.getValueAt(r, 0))) {
                            fiadosMarcadosOk.add(((Number) tmodel.getValueAt(r, 1)).longValue());
                        }
                    }
                    if (fiadosMarcadosOk.isEmpty()) {
                        throw new AppException("Marque pelo menos um lan\u00e7amento (coluna Pagar).");
                    }
                    BigDecimal valorPago = money(campoValor.getText());
                    BusinessRules.requirePositive(valorPago, "Valor a pagar");

                    BigDecimal maxSel = BigDecimal.ZERO;
                    for (Long fid : fiadosMarcadosOk) {
                        maxSel = maxSel.add(abertoPorFiadoId.getOrDefault(fid, BigDecimal.ZERO));
                    }
                    if (valorPago.compareTo(maxSel) > 0) {
                        throw new AppException("O valor (" + moneyText(valorPago)
                                + ") nao pode ser maior que a soma dos lan\u00e7amentos marcados ("
                                + moneyText(maxSel) + ").");
                    }
                    String formaOk = String.valueOf(formaPg.getSelectedItem());
                    if ("DINHEIRO".equals(formaOk)) {
                        BigDecimal recebidoOk = money(campoRecebidoDinheiro.getText());
                        if (recebidoOk.compareTo(valorPago) < 0) {
                            throw new AppException("Em dinheiro, o valor recebido precisa ser maior ou igual ao valor a pagar.");
                        }
                    }
                    valorConfirmado[0] = valorPago;
                    formaConfirmada[0] = formaOk;
                    dialog.dispose();
                } catch (AppException ex) {
                    JOptionPane.showMessageDialog(dialog, ex.getMessage(), "Baixa",
                            JOptionPane.WARNING_MESSAGE);
                } catch (Exception ex) {
                    error(ex);
                }
            });
            cancel.addActionListener(ev -> dialog.dispose());

            JPanel botoes = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            botoes.add(cancel);
            botoes.add(ok);

            JPanel sul = new JPanel(new BorderLayout(0, 8));
            sul.add(rodapeForm, BorderLayout.NORTH);
            sul.add(botoes, BorderLayout.SOUTH);

            dialog.add(norte, BorderLayout.NORTH);
            dialog.add(centro, BorderLayout.CENTER);
            dialog.add(sul, BorderLayout.SOUTH);

            atualizarTrocoBaixa[0].run();

            JRootPane rp = dialog.getRootPane();
            InputMap im = rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            ActionMap am = rp.getActionMap();
            im.put(KeyStroke.getKeyStroke("F5"), "fiadoMarcarLinha");
            am.put("fiadoMarcarLinha", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    int r = tabelaLanc.getSelectedRow();
                    if (r < 0) {
                        return;
                    }
                    long fid = ((Number) tmodel.getValueAt(r, 1)).longValue();
                    syncingChecks[0] = true;
                    try {
                        for (int rr = 0; rr < tmodel.getRowCount(); rr++) {
                            if (((Number) tmodel.getValueAt(rr, 1)).longValue() == fid) {
                                tmodel.setValueAt(Boolean.TRUE, rr, 0);
                            }
                        }
                    } finally {
                        syncingChecks[0] = false;
                    }
                    syncValorFromSelection.run();
                }
            });
            im.put(KeyStroke.getKeyStroke("F6"), "fiadoDesmarcarLinha");
            am.put("fiadoDesmarcarLinha", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    int r = tabelaLanc.getSelectedRow();
                    if (r < 0) {
                        return;
                    }
                    long fid = ((Number) tmodel.getValueAt(r, 1)).longValue();
                    syncingChecks[0] = true;
                    try {
                        for (int rr = 0; rr < tmodel.getRowCount(); rr++) {
                            if (((Number) tmodel.getValueAt(rr, 1)).longValue() == fid) {
                                tmodel.setValueAt(Boolean.FALSE, rr, 0);
                            }
                        }
                    } finally {
                        syncingChecks[0] = false;
                    }
                    syncValorFromSelection.run();
                }
            });
            im.put(KeyStroke.getKeyStroke("F7"), "fiadoMarcarTodos");
            am.put("fiadoMarcarTodos", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    syncingChecks[0] = true;
                    try {
                        for (int r = 0; r < tmodel.getRowCount(); r++) {
                            tmodel.setValueAt(Boolean.TRUE, r, 0);
                        }
                    } finally {
                        syncingChecks[0] = false;
                    }
                    syncValorFromSelection.run();
                }
            });
            im.put(KeyStroke.getKeyStroke("F8"), "fiadoDesmarcarTodos");
            am.put("fiadoDesmarcarTodos", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    syncingChecks[0] = true;
                    try {
                        for (int r = 0; r < tmodel.getRowCount(); r++) {
                            tmodel.setValueAt(Boolean.FALSE, r, 0);
                        }
                    } finally {
                        syncingChecks[0] = false;
                    }
                    syncValorFromSelection.run();
                }
            });

            dialog.pack();
            dialog.setMinimumSize(new Dimension(720, 460));
            dialog.setSize(920, 520);
            dialog.setLocationRelativeTo(frame);
            SwingUtilities.invokeLater(() -> {
                tabelaLanc.requestFocusInWindow();
                if (tabelaLanc.getRowCount() > 0) {
                    tabelaLanc.setRowSelectionInterval(0, 0);
                }
            });
            dialog.setVisible(true);

            if (valorConfirmado[0] == null) {
                return;
            }
            BigDecimal valorPago = valorConfirmado[0];
            String forma = formaConfirmada[0];

            Set<Long> idsMarcados = new HashSet<>();
            for (int r = 0; r < tmodel.getRowCount(); r++) {
                if (Boolean.TRUE.equals(tmodel.getValueAt(r, 0))) {
                    idsMarcados.add(((Number) tmodel.getValueAt(r, 1)).longValue());
                }
            }

            List<Map<String, Object>> abertosFiltrados = new ArrayList<>();
            for (Map<String, Object> linha : abertosLista) {
                long fid = ((Number) linha.get("id")).longValue();
                if (idsMarcados.contains(fid)) {
                    abertosFiltrados.add(linha);
                }
            }

            BigDecimal restante = valorPago;
            String hoje = LocalDateTime.now().toString();
            int lancamentosAfetados = 0;
            int lancamentosQuitados = 0;
            for (Map<String, Object> linha : abertosFiltrados) {
                if (restante.signum() <= 0) {
                    break;
                }
                long fiadoId = ((Number) linha.get("id")).longValue();
                BigDecimal valor = money(String.valueOf(linha.get("valor")));
                BigDecimal jaPago = money(String.valueOf(linha.get("valor_pago")));
                BigDecimal aberto = valor.subtract(jaPago).max(BigDecimal.ZERO);
                if (aberto.signum() <= 0) {
                    continue;
                }
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

    private void abrirDialogRegistrarPagamentoFinanceiro(JTable tabPendencias) {
        try {
            requireFinanceAccess();
            int vr = tabPendencias.getSelectedRow();
            if (vr < 0) {
                msg("Selecione um lancamento em aberto na tabela Pendencias por vencimento.");
                return;
            }
            int mr = tabPendencias.convertRowIndexToModel(vr);
            Object idObj = tabPendencias.getModel().getValueAt(mr, 0);
            if (!(idObj instanceof Number)) {
                msg("Nao foi possivel identificar o ID do lancamento.");
                return;
            }
            long lancamentoId = ((Number) idObj).longValue();
            Map<String, Object> row = one("""
                    select id, tipo, descricao, coalesce(parceiro, '') as parceiro,
                           valor_total, valor_baixado, status
                    from financeiro_lancamentos
                    where id = ? and status in ('ABERTO','PARCIAL')
                    """, lancamentoId);
            if (row == null || row.isEmpty()) {
                msg("Lancamento nao encontrado ou ja quitado.");
                return;
            }
            BigDecimal total = money(String.valueOf(row.get("valor_total")));
            BigDecimal baixado = money(String.valueOf(row.get("valor_baixado")));
            BigDecimal emAberto = total.subtract(baixado).setScale(2, RoundingMode.HALF_UP);
            if (emAberto.signum() <= 0) {
                msg("Este lancamento nao possui saldo em aberto.");
                return;
            }

            JDialog dialog = new JDialog(frame, "Registrar pagamento / status", true);
            dialog.setLayout(new BorderLayout(10, 10));
            ((JPanel) dialog.getContentPane()).setBorder(new EmptyBorder(10, 12, 10, 12));

            JPanel norte = new JPanel();
            norte.setLayout(new BoxLayout(norte, BoxLayout.Y_AXIS));
            norte.setOpaque(false);
            norte.add(new JLabel("Lancamento #" + lancamentoId + " | " + row.get("tipo")));
            norte.add(new JLabel(Objects.toString(row.get("descricao"), "")));
            norte.add(new JLabel("Parceiro: " + Objects.toString(row.get("parceiro"), "-")));
            norte.add(new JLabel("Em aberto: " + moneyText(emAberto)));
            norte.add(Box.createVerticalStrut(8));

            JRadioButton rbAberto = new JRadioButton("Manter em aberto (ainda nao pago — somente conferencia)", true);
            JRadioButton rbPago = new JRadioButton("Registrar pagamento (baixa no financeiro)");
            ButtonGroup grp = new ButtonGroup();
            grp.add(rbAberto);
            grp.add(rbPago);

            JPanel pForm = new JPanel(new GridBagLayout());
            pForm.setOpaque(false);
            JTextField campoValor = new JTextField(moneyInputText(emAberto));
            JComboBox<String> forma = new JComboBox<>(new String[]{"DINHEIRO", "PIX", "DEBITO", "CREDITO"});
            JTextField campoObs = new JTextField();

            Runnable syncCampos = () -> {
                boolean pago = rbPago.isSelected();
                campoValor.setEnabled(pago);
                forma.setEnabled(pago);
                campoObs.setEnabled(pago);
            };
            rbAberto.addActionListener(e -> syncCampos.run());
            rbPago.addActionListener(e -> syncCampos.run());

            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(4, 4, 4, 4);
            gc.anchor = GridBagConstraints.WEST;
            gc.gridx = 0;
            gc.gridy = 0;
            gc.gridwidth = 2;
            pForm.add(rbAberto, gc);
            gc.gridy = 1;
            pForm.add(rbPago, gc);
            gc.gridwidth = 1;
            gc.gridy = 2;
            gc.gridx = 0;
            pForm.add(new JLabel("Valor da baixa"), gc);
            gc.gridx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1;
            pForm.add(campoValor, gc);
            gc.gridy = 3;
            gc.gridx = 0;
            gc.fill = GridBagConstraints.NONE;
            gc.weightx = 0;
            pForm.add(new JLabel("Forma"), gc);
            gc.gridx = 1;
            pForm.add(forma, gc);
            gc.gridy = 4;
            gc.gridx = 0;
            pForm.add(new JLabel("Observacao"), gc);
            gc.gridx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1;
            pForm.add(campoObs, gc);
            syncCampos.run();

            JButton ok = new JButton("OK");
            JButton cancel = new JButton("Cancelar");
            dialog.getRootPane().setDefaultButton(ok);
            ok.addActionListener(ev -> {
                try {
                    if (rbAberto.isSelected()) {
                        JOptionPane.showMessageDialog(dialog,
                                "Lancamento permanece em aberto.",
                                "Financeiro",
                                JOptionPane.INFORMATION_MESSAGE);
                        dialog.dispose();
                        return;
                    }
                    BigDecimal val = money(campoValor.getText());
                    BusinessRules.requirePositive(val, "Valor da baixa");
                    if (val.compareTo(emAberto) > 0) {
                        throw new AppException("O valor excede o saldo em aberto (" + moneyText(emAberto) + ").");
                    }
                    String formaSel = String.valueOf(forma.getSelectedItem());
                    financeService.settle(lancamentoId, val, formaSel, campoObs.getText());
                    audit("FINANCEIRO_BAIXA", "Lancamento #" + lancamentoId + " | " + formaSel);
                    dialog.dispose();
                    refreshFrame();
                } catch (AppException ex) {
                    JOptionPane.showMessageDialog(dialog, ex.getMessage(), "Baixa", JOptionPane.WARNING_MESSAGE);
                } catch (Exception ex) {
                    error(ex);
                }
            });
            cancel.addActionListener(ev -> dialog.dispose());

            JPanel botoes = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            botoes.add(cancel);
            botoes.add(ok);

            dialog.add(norte, BorderLayout.NORTH);
            dialog.add(pForm, BorderLayout.CENTER);
            dialog.add(botoes, BorderLayout.SOUTH);
            dialog.pack();
            dialog.setLocationRelativeTo(frame);
            dialog.setVisible(true);
        } catch (Exception ex) {
            error(ex);
        }
    }

    private boolean managerPin(String pin) throws Exception {
        for (Map<String, Object> row : rows("select pin_hash from usuarios where role in ('ADMIN','GERENTE') and ativo=1 and pin_hash is not null")) {
            if (encoder.matches(pin == null ? "" : pin, row.get("pin_hash").toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Celulas de {@link #table(String, Object...)} e {@link #reloadTableSql}:
     * evita "575.8199999999999" (REAL/double do SQLite) arredondando em 2 casas
     * e exibindo inteiros sem ",00" desnecessario.
     */
    private Object formatTableSqlCell(Object value) {
        if (value instanceof Number n) {
            return formatTableNumberCell(n);
        }
        if (value != null && value.toString().matches("\\d{4}-\\d{2}-\\d{2}T.*")) {
            return LocalDateTime.parse(value.toString()).format(BR_DATE_TIME);
        }
        return value;
    }

    private Object formatTableNumberCell(Number n) {
        if (n == null) {
            return null;
        }
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
            return n;
        }
        BigDecimal bd = n instanceof BigDecimal b ? b : BigDecimal.valueOf(n.doubleValue());
        bd = bd.setScale(2, RoundingMode.HALF_UP);
        BigDecimal stripped = bd.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            return stripped.longValue();
        }
        return moneyInputText(bd);
    }

    private JTable table(String sql, Object... args) {
        try {
            return table(rows(sql, args));
        } catch (Exception e) {
            // Carregar tabela para visualizacao nao e operacao critica: logar e
            // seguir com tabela vazia (evita dialogo de "erro inesperado" ao abrir
            // o sistema quando uma query opcional falha ou dado vem em formato inesperado).
            e.printStackTrace();
            String actor = user == null ? "anonimo" : user.login + "/" + user.role;
            String sqlHint = sql.length() > 220 ? sql.substring(0, 220) + "..." : sql;
            SupportLogger.logException("table-load", e, "usuario=" + actor + " | " + sqlHint);
            appLog("WARN", "TABLE_LOAD", e.getClass().getSimpleName(),
                    "usuario=" + actor + " | " + (e.getMessage() == null ? "" : e.getMessage()));
            return new JTable();
        }
    }

    private JTable table(List<Map<String, Object>> data) {
        Vector<String> cols = new Vector<>();
        Vector<Vector<Object>> lines = new Vector<>();
        if (!data.isEmpty()) {
            cols.addAll(data.get(0).keySet());
        }
        for (Map<String, Object> row : data) {
            Vector<Object> line = new Vector<>();
            for (Object value : row.values()) {
                line.add(formatTableSqlCell(value));
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
    }

    private JComboBox<Item> combo(String sql) {
        JComboBox<Item> combo = new JComboBox<>();
        combo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        try {
            for (Map<String, Object> row : rows(sql)) {
                combo.addItem(new Item(((Number) row.values().toArray()[0]).longValue(), row.values().toArray()[1].toString()));
            }
        } catch (Exception e) {
            e.printStackTrace();
            String actor = user == null ? "anonimo" : user.login + "/" + user.role;
            SupportLogger.logException("combo-load", e, "usuario=" + actor);
            appLog("WARN", "COMBO_LOAD", e.getClass().getSimpleName(),
                    "usuario=" + actor + " | " + (e.getMessage() == null ? "" : e.getMessage()));
        }
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

    /**
     * Campos de texto, botoes e paineis nao repassam a roda do mouse ao
     * {@link JScrollPane} pai; a barra aparece mas a roda nao rola. Registramos
     * um listener recursivo que move a barra vertical do scroll ancestral mais
     * proximo (tabela interna em card, painel direito do PDV ou rolagem da aba).
     */
    private static void installPdvMouseWheelScrollForwarding(Component root) {
        if (root instanceof JScrollPane sp) {
            Component view = sp.getViewport() == null ? null : sp.getViewport().getView();
            if (view != null) {
                installPdvMouseWheelScrollForwarding(view);
            }
            return;
        }
        if (root instanceof JTable || root instanceof JComboBox<?> || root instanceof JList<?>) {
            return;
        }
        root.addMouseWheelListener(PdvMouseWheelScrollForwarder.INSTANCE);
        if (root instanceof Container c) {
            for (Component child : c.getComponents()) {
                installPdvMouseWheelScrollForwarding(child);
            }
        }
    }

    private static final class PdvMouseWheelScrollForwarder implements MouseWheelListener {
        static final PdvMouseWheelScrollForwarder INSTANCE = new PdvMouseWheelScrollForwarder();

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            Component src = (Component) e.getSource();
            Component anc = SwingUtilities.getAncestorOfClass(JScrollPane.class, src);
            if (!(anc instanceof JScrollPane scroll)) {
                return;
            }
            JScrollBar bar = scroll.getVerticalScrollBar();
            if (bar == null || !bar.isEnabled()) {
                return;
            }
            int max = bar.getMaximum() - bar.getModel().getExtent();
            if (max <= bar.getMinimum()) {
                return;
            }
            int oldVal = bar.getValue();
            int delta;
            if (e.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL) {
                delta = e.getUnitsToScroll() * bar.getUnitIncrement();
            } else {
                delta = e.getWheelRotation() * bar.getBlockIncrement();
            }
            int next = Math.max(bar.getMinimum(), Math.min(max, oldVal + delta));
            if (next != oldVal) {
                bar.setValue(next);
                e.consume();
            }
        }
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

    /** Label de formulario que nao encolhe abaixo do texto (evita "cortar" em janelas estreitas). */
    private JLabel labelNonShrinking(String text) {
        JLabel l = label(text);
        Dimension d = l.getPreferredSize();
        l.setMinimumSize(new Dimension(Math.max(d.width, scale(52)), d.height));
        return l;
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
        form.add(labelNonShrinking(label1), gc);

        gc.gridx = 1;
        gc.weightx = 0;
        form.add(field1, gc);

        gc.gridx = 2;
        gc.weightx = 0;
        gc.insets = new Insets(3, 14, 3, 6);
        form.add(labelNonShrinking(label2), gc);

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
        form.add(labelNonShrinking(label1), gc);

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
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setFont(new Font("Segoe UI", Font.BOLD, fontSize(compactMode() ? 12 : 13)));
        button.setForeground(dark ? Color.WHITE : new Color(0x1B, 0x1B, 0x1B));
        button.setBorder(new EmptyBorder(compactMode() ? 6 : 7, 10, compactMode() ? 6 : 7, 10));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, compactMode() ? 34 : 40));
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

    /** Cartao branco na coluna direita do PDV (dois blocos sobre fundo cinza, estilo caixa fisico). */
    private JPanel pdvCaixaSideCard() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(true);
        p.setBackground(PANEL_BG);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        int ip = compactMode() ? 10 : 14;
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT),
                new EmptyBorder(ip, ip, ip, ip)));
        return p;
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

        JLabel brand = new JLabel(APP_BRAND_NAME);
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
        return metricCard(label, value, color, null, null);
    }

    /**
     * KPI card no estilo PDV verde: pill colorido com icone (canto esq. sup.),
     * trend percentual a direita (verde +/ vermelho -), valor grande preto e label cinza.
     */
    private JPanel metricCard(String label, String value, Color color, String trend) {
        return metricCard(label, value, color, trend, null);
    }

    private JPanel metricCard(String label, String value, Color color, String trend, JLabel[] valueLabelOut) {
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
        if (valueLabelOut != null && valueLabelOut.length > 0) {
            valueLabelOut[0] = v;
        }
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

    /**
     * Miniatura na primeira coluna (PDV / Estoque): nao usar {@code Icon.class} em
     * {@link DefaultTableModel#getColumnClass(int)} junto com {@link TableRowSorter},
     * senao o Nimbus pode pintar {@code ImageIcon#toString()} em colunas de texto.
     */
    private static void installPdvThumbColumnRenderer(JTable table, int thumbCol) {
        DefaultTableCellRenderer r = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {
                JLabel lb = (JLabel) super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
                lb.setText(null);
                lb.setIcon(null);
                if (value instanceof Icon icon) {
                    lb.setIcon(icon);
                }
                lb.setHorizontalAlignment(SwingConstants.CENTER);
                return lb;
            }
        };
        table.getColumnModel().getColumn(thumbCol).setCellRenderer(r);
        if (table.getAutoCreateRowSorter() && table.getRowSorter() instanceof TableRowSorter<?> sorter) {
            sorter.setSortable(thumbCol, false);
        }
    }

    /** Ao sair do campo, normaliza para o padrao de digitacao em Reais (ex.: 450,00). */
    private void addMoneyFormatOnFocusLost(JTextField field) {
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                String t = field.getText();
                if (t == null || t.isBlank()) {
                    return;
                }
                try {
                    field.setText(moneyInputText(money(t)));
                } catch (Exception ignored) {
                    // mantem o texto para o operador corrigir
                }
            }
        });
    }

    /**
     * Mantem a coluna de valores das parcelas sempre no formato pt-BR apos edicao
     * (mesmo padrao dos campos Valor total / Taxa).
     */
    private void attachParcelasValorFormatoBr(DefaultTableModel modelParcelas) {
        final boolean[] reentrando = {false};
        modelParcelas.addTableModelListener(e -> {
            if (reentrando[0] || e.getColumn() != 1) {
                return;
            }
            if (e.getType() != TableModelEvent.UPDATE) {
                return;
            }
            int r0 = e.getFirstRow();
            int r1 = e.getLastRow();
            for (int r = r0; r <= r1; r++) {
                if (r < 0 || r >= modelParcelas.getRowCount()) {
                    continue;
                }
                Object o = modelParcelas.getValueAt(r, 1);
                if (o == null) {
                    continue;
                }
                String s = o.toString().trim();
                if (s.isEmpty()) {
                    continue;
                }
                try {
                    String n = moneyInputText(money(s));
                    if (!n.equals(s)) {
                        reentrando[0] = true;
                        try {
                            modelParcelas.setValueAt(n, r, 1);
                        } finally {
                            reentrando[0] = false;
                        }
                    }
                } catch (Exception ignored) {
                    // ignora celula invalida ate o operador corrigir
                }
            }
        });
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
        final Color headerGrid = new Color(0xD8, 0xD8, 0xDC);
        header.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected, boolean hasFocus,
                    int row, int column) {
                JLabel lb = (JLabel) super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
                lb.setHorizontalAlignment(SwingConstants.LEFT);
                lb.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 0, 1, headerGrid),
                        new EmptyBorder(5, 10, 5, 10)));
                lb.setBackground(new Color(0xEE, 0xEE, 0xEE));
                lb.setForeground(TEXT_DARK);
                lb.setOpaque(true);
                return lb;
            }
        });

        table.setGridColor(new Color(0xD8, 0xD8, 0xDC));
        // Linhas zebradas via Nimbus + selecao verde claro com texto verde.
        table.setSelectionBackground(MARKET_GREEN_SOFT);
        table.setSelectionForeground(MARKET_GREEN);
        // Grade vertical + horizontal (estilo ID | Interno | Produto).
        table.setShowVerticalLines(true);
        table.setShowHorizontalLines(true);
        // Espacamento um pouco maior entre colunas/linhas para nao ficar "tudo colado".
        table.setIntercellSpacing(new Dimension(4, 2));
        // Sem preencher o viewport com linhas vazias: o espaco abaixo da ultima linha fica "livre".
        table.setFillsViewportHeight(false);
        // Nao sobrescrevemos renderers per-coluna: preservamos badges/status/botoes
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
            UIManager.put("Table.gridColor", new Color(0xD8, 0xD8, 0xDC));
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

    /**
     * Densidade da interface: {@code auto} segue o monitor; ou use propriedade JVM
     * {@code -Dmercadotonico.uiDensity=...} / preferencia {@code ui.density} no no desktop.
     * Valores: {@code auto}, {@code comfort} (telas grandes), {@code compact}, {@code ultra}.
     */
    private String uiDensityOverrideSpec() {
        String sys = System.getProperty("mercadotonico.uiDensity", "").trim().toLowerCase(Locale.ROOT);
        if (!sys.isEmpty()) {
            return sys;
        }
        String p = DESKTOP_PREFS.get(PREF_UI_DENSITY, "auto").trim().toLowerCase(Locale.ROOT);
        return p.isEmpty() ? "auto" : p;
    }

    private boolean compactMode() {
        String o = uiDensityOverrideSpec();
        if ("comfort".equals(o) || "spacious".equals(o) || "large".equals(o)) {
            return false;
        }
        if ("compact".equals(o) || "small".equals(o) || "ultra".equals(o) || "tiny".equals(o)) {
            return true;
        }
        return uiReferenceSize.width <= 1536 || uiReferenceSize.height <= 900;
    }

    /**
     * Notebooks pequenos (1280x720, 1366x768) ou janelas restauradas em
     * resolucoes ainda menores: aplicamos paddings e fontes mais agressivos
     * e escondemos elementos secundarios (data por extenso, subtitulos).
     */
    private boolean ultraCompactMode() {
        String o = uiDensityOverrideSpec();
        if ("ultra".equals(o) || "tiny".equals(o)) {
            return true;
        }
        if ("comfort".equals(o) || "spacious".equals(o) || "large".equals(o) || "normal".equals(o)) {
            return false;
        }
        if ("compact".equals(o) || "small".equals(o)) {
            return false;
        }
        return uiReferenceSize.width <= 1366 || uiReferenceSize.height <= 768;
    }

    private double uiScaleFactor() {
        String o = uiDensityOverrideSpec();
        if ("comfort".equals(o) || "spacious".equals(o) || "large".equals(o)) {
            int h = uiReferenceSize.height;
            return h >= 1200 ? 1.10 : 1.06;
        }
        if ("ultra".equals(o) || "tiny".equals(o)) {
            return 0.78;
        }
        if ("compact".equals(o) || "small".equals(o)) {
            return 0.92;
        }
        if (uiReferenceSize.width <= 1280 || uiReferenceSize.height <= 720) {
            return 0.78;
        }
        if (uiReferenceSize.width <= 1366 || uiReferenceSize.height <= 768) {
            return 0.84;
        }
        if (uiReferenceSize.width <= 1536 || uiReferenceSize.height <= 900) {
            return 0.90;
        }
        int h = uiReferenceSize.height;
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
        if (frame != null) {
            try {
                if (frame.isDisplayable()) {
                    GraphicsConfiguration gc = frame.getGraphicsConfiguration();
                    if (gc != null) {
                        Rectangle b = gc.getBounds();
                        uiReferenceSize.setSize(b.width, b.height);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        buildFrame();
    }

    /**
     * Normaliza {@code caixas.abertura_timestamp} para filtro {@code >= desde}.
     * {@code null} ou vazio faz o PDV usar o dia civil atual (compatibilidade).
     */
    private static String normalizeAberturaTimestamp(Object aberturaTimestamp) {
        if (aberturaTimestamp == null) {
            return null;
        }
        String s = aberturaTimestamp.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Inicio do turno no caixa aberto (filtro {@code >=} nas vendas/operacoes).
     * Sem carimbo gravado (legado), usa meia-noite do dia civil local para o
     * resumo nao mudar ao fechar e reabrir o programa no mesmo turno.
     */
    private String effectiveDesdeTurno(Object aberturaTimestamp) {
        String s = normalizeAberturaTimestamp(aberturaTimestamp);
        if (s != null && !s.isEmpty()) {
            return s;
        }
        return LocalDate.now().atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private void rememberPdvCaixaSelection() {
        if (caixaCombo == null) {
            return;
        }
        Item sel = (Item) caixaCombo.getSelectedItem();
        if (sel != null) {
            DESKTOP_PREFS.putLong(PREF_PDV_CAIXA_ID, sel.id);
        }
    }

    private void restorePdvCaixaSelection() {
        if (caixaCombo == null) {
            return;
        }
        long id = DESKTOP_PREFS.getLong(PREF_PDV_CAIXA_ID, -1L);
        if (id < 0L) {
            return;
        }
        for (int i = 0; i < caixaCombo.getItemCount(); i++) {
            Item it = caixaCombo.getItemAt(i);
            if (it != null && it.id == id) {
                caixaCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    private static LocalDate parseCaixaAberturaParaLocalDate(Object aberturaTimestamp) {
        if (aberturaTimestamp == null) {
            return null;
        }
        String s = aberturaTimestamp.toString().trim();
        if (s.length() >= 10 && s.charAt(4) == '-') {
            try {
                return LocalDate.parse(s.substring(0, 10));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private BigDecimal cardSalesPdvWindow(long caixaId, String desdeAberturaOuNull) {
        try {
            Map<String, Object> row;
            if (desdeAberturaOuNull != null) {
                row = one("""
                    select coalesce(sum(vp.valor),0) as total
                    from venda_pagamentos vp
                    join vendas v on v.id = vp.venda_id
                    where v.caixa_id = ?
                      and v.status = 'CONCLUIDA'
                      and vp.forma in ('DEBITO', 'CREDITO')
                      and strftime('%s', v.timestamp) >= strftime('%s', ?)
                    """, caixaId, desdeAberturaOuNull);
            } else {
                row = one("""
                    select coalesce(sum(vp.valor),0) as total
                    from venda_pagamentos vp
                    join vendas v on v.id = vp.venda_id
                    where v.caixa_id = ?
                      and v.status = 'CONCLUIDA'
                      and vp.forma in ('DEBITO', 'CREDITO')
                      and date(v.timestamp) = date('now', 'localtime')
                    """, caixaId);
            }
            return row == null ? BigDecimal.ZERO : money(String.valueOf(row.get("total")));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal pixSalesPdvWindow(long caixaId, String desdeAberturaOuNull) {
        try {
            Map<String, Object> row;
            if (desdeAberturaOuNull != null) {
                row = one("""
                    select coalesce(sum(vp.valor),0) as total
                    from venda_pagamentos vp
                    join vendas v on v.id = vp.venda_id
                    where v.caixa_id = ?
                      and v.status = 'CONCLUIDA'
                      and vp.forma = 'PIX'
                      and strftime('%s', v.timestamp) >= strftime('%s', ?)
                    """, caixaId, desdeAberturaOuNull);
            } else {
                row = one("""
                    select coalesce(sum(vp.valor),0) as total
                    from venda_pagamentos vp
                    join vendas v on v.id = vp.venda_id
                    where v.caixa_id = ?
                      and v.status = 'CONCLUIDA'
                      and vp.forma = 'PIX'
                      and date(v.timestamp) = date('now', 'localtime')
                    """, caixaId);
            }
            return row == null ? BigDecimal.ZERO : money(String.valueOf(row.get("total")));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Soma operacoes de caixa do tipo no turno ({@code desde}) ou no dia civil, se {@code desde} for null.
     */
    private BigDecimal caixaOperacoesPdvWindow(long caixaId, String tipo, String desdeAberturaOuNull) throws Exception {
        Map<String, Object> row;
        if (desdeAberturaOuNull != null) {
            row = one("""
                    select coalesce(sum(valor),0) as t from caixa_operacoes
                    where caixa_id = ? and tipo = ? and strftime('%s', timestamp) >= strftime('%s', ?)
                    """, caixaId, tipo, desdeAberturaOuNull);
        } else {
            row = one("""
                    select coalesce(sum(valor),0) as t from caixa_operacoes
                    where caixa_id = ? and tipo = ? and date(timestamp) = date('now', 'localtime')
                    """, caixaId, tipo);
        }
        return row == null ? BigDecimal.ZERO : money(String.valueOf(row.get("t")));
    }

    /**
     * Dinheiro fisico estimado no caixa aberto: abertura + vendas em dinheiro no turno
     * + suprimentos - sangrias (mesma janela do PDV apos meia-noite).
     */
    private BigDecimal saldoDinheiroFisicoNoCaixa(long caixaId) throws Exception {
        Map<String, Object> cx = one("""
                select status, coalesce(abertura_valor,0) as abertura_valor, abertura_timestamp
                from caixas where id=?
                """, caixaId);
        if (cx == null || !"ABERTO".equals(String.valueOf(cx.get("status")))) {
            return BigDecimal.ZERO;
        }
        BigDecimal abertura = money(String.valueOf(cx.get("abertura_valor")));
        String desde = effectiveDesdeTurno(cx.get("abertura_timestamp"));
        return abertura
                .add(cashSalesPdvWindow(caixaId, desde))
                .add(caixaOperacoesPdvWindow(caixaId, "SUPRIMENTO", desde))
                .subtract(caixaOperacoesPdvWindow(caixaId, "SANGRIA", desde));
    }

    /**
     * Total em DINHEIRO (vendas CONCLUIDAS) no turno desde {@code abertura_timestamp},
     * ou no dia civil se {@code desdeAberturaOuNull} for null. Usado no "Fundo" do PDV.
     */
    private BigDecimal cashSalesPdvWindow(long caixaId, String desdeAberturaOuNull) {
        try {
            Map<String, Object> row;
            if (desdeAberturaOuNull != null) {
                row = one("""
                    select coalesce(sum(vp.valor),0) as total
                    from venda_pagamentos vp
                    join vendas v on v.id = vp.venda_id
                    where v.caixa_id = ?
                      and v.status = 'CONCLUIDA'
                      and vp.forma = 'DINHEIRO'
                      and strftime('%s', v.timestamp) >= strftime('%s', ?)
                    """, caixaId, desdeAberturaOuNull);
            } else {
                row = one("""
                    select coalesce(sum(vp.valor),0) as total
                    from venda_pagamentos vp
                    join vendas v on v.id = vp.venda_id
                    where v.caixa_id = ?
                      and v.status = 'CONCLUIDA'
                      and vp.forma = 'DINHEIRO'
                      and date(v.timestamp) = date('now', 'localtime')
                    """, caixaId);
            }
            return row == null ? BigDecimal.ZERO : money(String.valueOf(row.get("total")));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * No PDV, o dialogo de aumento de limite de convenio aceita a senha real
     * de login de um usuario ADMIN ou GERENTE ativo.
     */

    /** Dias a partir da data da compra mais antiga em aberto para quitar o convênio; após isso bloqueia novas compras FIADO. */
    private static final int FIADO_PRAZO_PAGAMENTO_DIAS = 30;

    /**
     * Data da criacao do lancamento de fiado (coluna {@code data_criacao}) em data local.
     */
    private static LocalDate parseDataCriacaoFiadoParaLocalDate(String dataCriacao) {
        if (dataCriacao == null || dataCriacao.isBlank()) {
            return LocalDate.now(ZoneId.systemDefault());
        }
        String s = dataCriacao.trim();
        try {
            if (s.length() >= 19 && s.charAt(4) == '-') {
                return LocalDateTime.parse(s.substring(0, 19)).toLocalDate();
            }
            if (s.length() >= 10 && s.charAt(4) == '-') {
                return LocalDate.parse(s.substring(0, 10));
            }
        } catch (Exception ignored) {
            // cai no fallback abaixo
        }
        try {
            return LocalDateTime.parse(s).toLocalDate();
        } catch (Exception e2) {
            return LocalDate.now(ZoneId.systemDefault());
        }
    }

    /**
     * Valida prazo de pagamento do convenio (30 dias a partir
     * da compra mais antiga ainda em aberto), depois o limite de credito.
     * Aumentar limite nao contorna o prazo vencido.
     * Se estourar o limite (e dentro do prazo), oferece ao operador aumentar o limite com senha de administrador.
     *
     * @return {@code true} se a venda pode prosseguir, {@code false} se bloqueada ou cancelada.
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
            if (saldoAberto.compareTo(BigDecimal.ZERO) > 0) {
                Map<String, Object> primeiraRow = one("""
                        select min(f.data_criacao) as primeira
                        from fiado f
                        where f.cliente_id = ?
                          and f.status = 'ABERTO'
                          and (f.valor - f.valor_pago) > 0
                        """, clienteId);
                if (primeiraRow != null && primeiraRow.get("primeira") != null) {
                    String raw = primeiraRow.get("primeira").toString().trim();
                    if (!raw.isEmpty()) {
                        LocalDate diaCompra = parseDataCriacaoFiadoParaLocalDate(raw);
                        LocalDate ultimoDiaParaQuitar = diaCompra.plusDays(FIADO_PRAZO_PAGAMENTO_DIAS);
                        LocalDate hoje = LocalDate.now(ZoneId.systemDefault());
                        if (hoje.isAfter(ultimoDiaParaQuitar)) {
                            msg("""
                                    Prazo de %d dias para quitar o convenio vencido.
                                    Cliente: %s
                                    Compra mais antiga em aberto: %s
                                    Pagar ate: %s (hoje: %s).
                                    Quite o saldo em aberto (%s) para voltar a comprar no convenio; o limite nao libera ate quitar.
                                    """.formatted(
                                    FIADO_PRAZO_PAGAMENTO_DIAS,
                                    nomeCliente,
                                    diaCompra.format(BR_DATE),
                                    ultimoDiaParaQuitar.format(BR_DATE),
                                    hoje.format(BR_DATE),
                                    moneyText(saldoAberto)));
                            return false;
                        }
                    }
                }
            }
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
        form.add(new JLabel("Senha de autorizacao ou Admin/Gerente:"), gc);
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
            boolean autorizado;
            try {
                autorizado = adminSenhaConfere(senha.getPassword());
            } catch (Exception ex) {
                status.setText("Erro ao validar senha: " + ex.getMessage());
                return;
            }
            if (!autorizado) {
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

    private record CartItem(long produtoId, String nome, BigDecimal qtd, BigDecimal preco, BigDecimal desconto, String imagemUrl) {
        CartItem(long produtoId, String nome, BigDecimal qtd, BigDecimal preco) {
            this(produtoId, nome, qtd, preco, BigDecimal.ZERO, null);
        }

        BigDecimal valorBrutoLinha() {
            return qtd.multiply(preco);
        }

        BigDecimal valorLiquidoLinha() {
            return valorBrutoLinha().subtract(desconto).max(BigDecimal.ZERO);
        }
    }
}
