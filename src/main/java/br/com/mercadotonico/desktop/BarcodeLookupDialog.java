package br.com.mercadotonico.desktop;

import br.com.mercadotonico.core.SupportLogger;
import br.com.mercadotonico.integration.barcode.BarcodeLookupResult;
import br.com.mercadotonico.integration.barcode.BarcodeLookupService;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.LongFunction;

/**
 * Dialogo de cadastro automatico de produto por codigo de barras.
 *
 * <p>Fluxo (igual a sistemas profissionais de mercado):
 * <ol>
 *   <li>Operador digita ou escaneia o EAN/GTIN no campo de cima.</li>
 *   <li>Apos {@link #SEARCH_DEBOUNCE_MS}ms sem alteracao (ou Enter), o
 *       sistema chama {@link BarcodeLookupService#lookup(String)} em
 *       thread de background ({@link SwingWorker}) para nao travar a EDT.</li>
 *   <li>Se ja existir em {@code produtos}, o form trava em modo somente
 *       leitura com aviso "Produto ja cadastrado" e botao "Editar" libera os
 *       campos (UPDATE no salvar).</li>
 *   <li>Se vier de uma API/cache, o form fica preenchido e habilitado para
 *       o operador completar custo / preco / estoque / validade.</li>
 *   <li>Se nao for encontrado em lugar nenhum, o form fica vazio e
 *       habilitado com aviso "Cadastro manual" — sem bloquear o usuario.</li>
 * </ol>
 *
 * <p>Compativel com leitor de codigo de barras USB padrao HID: leitores
 * disparam os digitos rapidamente seguidos de Enter. O Enter no campo
 * de cima dispara a busca imediata (sem esperar o debounce).</p>
 *
 * <p>Imagem do produto e baixada em background via {@link ImageIO}; offline
 * ou link quebrado sao silenciosamente ignorados.</p>
 */
public final class BarcodeLookupDialog extends JDialog {

    private static final int SEARCH_DEBOUNCE_MS = 350;
    private static final long PRICE_MARKUP_PERCENT = 30L; // sugestao venda = custo + 30%

    // Paleta sincronizada com DesktopApp para nao desalinhar o tema verde.
    private static final Color GREEN       = new Color(0x1B, 0x5E, 0x20);
    private static final Color GREEN_2     = new Color(0x2E, 0x7D, 0x32);
    private static final Color GREEN_SOFT  = new Color(0xE8, 0xF5, 0xE9);
    private static final Color ORANGE      = new Color(0xFF, 0xA7, 0x26);
    private static final Color RED         = new Color(0xC6, 0x28, 0x28);
    private static final Color BORDER_SOFT = new Color(0xE0, 0xE0, 0xE0);
    private static final Color TEXT_DARK   = new Color(0x21, 0x21, 0x21);
    private static final Color TEXT_MUTED  = new Color(0x61, 0x61, 0x61);
    private static final Color BG          = new Color(0xF5, 0xF5, 0xF5);

    private static final DecimalFormat MONEY_FMT =
            new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("pt-BR")));

    private final Connection con;
    private final BarcodeLookupService lookupService;
    private final DesktopInventoryService inventoryService;
    private final long operatorId;
    private final LongFunction<String> codigoInternoSupplier;
    private final Runnable onProductSaved;

    // ----- componentes da UI (referenciados pelos handlers) -----
    private final JTextField fldBarcode = new JTextField();
    private final JTextField fldName = new JTextField();
    private final JTextField fldBrand = new JTextField();
    private final JTextField fldManufacturer = new JTextField();
    private final JComboBox<String> cmbCategory = new JComboBox<>();
    private final JComboBox<String> cmbUnit = new JComboBox<>(
            new String[]{"un", "kg", "g", "L", "ml", "cx", "pct"});
    private final JTextField fldNcm = new JTextField();
    private final JTextField fldCest = new JTextField();
    private final JTextField fldCusto = new JTextField("0,00");
    private final JTextField fldVenda = new JTextField("0,00");
    private final JTextField fldEstoque = new JTextField("0");
    private final JTextField fldEstoqueMinimo = new JTextField("0");
    private final JTextField fldPrateleira = new JTextField();
    private final JTextField fldValidade = new JTextField(LocalDate.now().plusMonths(6).toString());
    private final JTextField fldObservacao = new JTextField();
    private final JLabel lblImage = new JLabel();
    private final JLabel lblStatus = new JLabel(" ");
    private final JLabel lblSourceBadge = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar();
    private final JButton btnSearch;
    private final JButton btnSave;
    private final JButton btnEdit;
    private final JButton btnClear;
    private final JButton btnClose;

    // ----- estado -----
    private final Timer searchDebounce;
    private SwingWorker<BarcodeLookupService.Outcome, Void> currentSearch;
    private SwingWorker<BufferedImage, Void> currentImageWorker;
    private Long existingProductId; // !=null quando o EAN ja existe em produtos
    private String pendingImageUrl;

    public BarcodeLookupDialog(Window owner,
                               Connection con,
                               BarcodeLookupService lookupService,
                               DesktopInventoryService inventoryService,
                               long operatorId,
                               LongFunction<String> codigoInternoSupplier,
                               Runnable onProductSaved) {
        super(owner, "Cadastro automatico por codigo de barras", ModalityType.APPLICATION_MODAL);
        this.con = con;
        this.lookupService = lookupService;
        this.inventoryService = inventoryService;
        this.operatorId = operatorId;
        this.codigoInternoSupplier = codigoInternoSupplier;
        this.onProductSaved = onProductSaved == null ? () -> {} : onProductSaved;

        this.btnSearch = primaryButton("\uD83D\uDD0D  Buscar", GREEN_2);
        this.btnSave   = primaryButton("\uD83D\uDCBE  Salvar Produto", GREEN_2);
        this.btnEdit   = primaryButton("\u270F  Editar", ORANGE);
        this.btnClear  = secondaryButton("Limpar");
        this.btnClose  = primaryButton("Fechar", RED);

        this.searchDebounce = new Timer(SEARCH_DEBOUNCE_MS, e -> performSearch());
        this.searchDebounce.setRepeats(false);

        buildUi();
        wireBehavior();
        loadCategorias();
        clearForm(true);
        setSize(scaledSize(940, 640));
        setLocationRelativeTo(owner);
    }

    // -----------------------------------------------------------------
    // Construcao da UI
    // -----------------------------------------------------------------

    private void buildUi() {
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout());

        add(buildHeader(), BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(12, 12));
        center.setBackground(BG);
        center.setBorder(new EmptyBorder(12, 16, 12, 16));
        center.add(buildSearchBar(), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(12, 12));
        body.setBackground(BG);
        body.add(buildPreview(), BorderLayout.WEST);
        body.add(buildForm(), BorderLayout.CENTER);
        center.add(body, BorderLayout.CENTER);

        add(center, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(0, 0, GREEN, getWidth(), 0, GREEN_2));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(14, 20, 14, 20));
        JLabel title = new JLabel("\uD83D\uDD0D  Cadastrar produto por codigo de barras");
        title.setFont(new Font("Segoe UI Emoji", Font.BOLD, 18));
        title.setForeground(Color.WHITE);
        JLabel subtitle = new JLabel("Escaneie ou digite o EAN/GTIN, os dados sao preenchidos automaticamente.");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subtitle.setForeground(new Color(0xC8, 0xE6, 0xC9));
        JPanel block = new JPanel();
        block.setOpaque(false);
        block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        block.add(title);
        block.add(Box.createVerticalStrut(2));
        block.add(subtitle);
        header.add(block, BorderLayout.WEST);
        return header;
    }

    private JPanel buildSearchBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 6));
        bar.setOpaque(false);

        // Campo grande de codigo de barras (foco automatico, leitor USB friendly).
        fldBarcode.setFont(new Font("Segoe UI", Font.BOLD, 26));
        fldBarcode.setForeground(TEXT_DARK);
        fldBarcode.setBackground(Color.WHITE);
        fldBarcode.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GREEN_2, 2),
                new EmptyBorder(10, 14, 10, 14)));
        fldBarcode.setToolTipText("Escaneie ou digite o codigo de barras (EAN-13 / GTIN). Enter dispara a busca.");

        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(0, 4));
        progressBar.setStringPainted(false);
        progressBar.setBorderPainted(false);
        progressBar.setForeground(GREEN_2);

        JPanel searchTop = new JPanel(new BorderLayout(8, 0));
        searchTop.setOpaque(false);
        searchTop.add(fldBarcode, BorderLayout.CENTER);
        searchTop.add(btnSearch, BorderLayout.EAST);

        JPanel statusRow = new JPanel(new BorderLayout(8, 0));
        statusRow.setOpaque(false);
        lblStatus.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblStatus.setForeground(TEXT_MUTED);
        lblStatus.setBorder(new EmptyBorder(4, 2, 0, 2));
        lblSourceBadge.setOpaque(true);
        lblSourceBadge.setBackground(GREEN_SOFT);
        lblSourceBadge.setForeground(GREEN);
        lblSourceBadge.setFont(new Font("Segoe UI", Font.BOLD, 11));
        lblSourceBadge.setBorder(new EmptyBorder(3, 8, 3, 8));
        lblSourceBadge.setVisible(false);
        statusRow.add(lblStatus, BorderLayout.CENTER);
        statusRow.add(lblSourceBadge, BorderLayout.EAST);

        JPanel container = new JPanel();
        container.setOpaque(false);
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        searchTop.setAlignmentX(Component.LEFT_ALIGNMENT);
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        container.add(searchTop);
        container.add(Box.createVerticalStrut(4));
        container.add(progressBar);
        container.add(statusRow);

        bar.add(container, BorderLayout.CENTER);
        return bar;
    }

    private JPanel buildPreview() {
        JPanel preview = new JPanel(new BorderLayout(0, 8));
        preview.setBackground(Color.WHITE);
        preview.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT),
                new EmptyBorder(10, 10, 10, 10)));
        preview.setPreferredSize(new Dimension(220, 240));

        JLabel head = new JLabel("Pre-visualizacao");
        head.setFont(new Font("Segoe UI", Font.BOLD, 12));
        head.setForeground(TEXT_MUTED);
        preview.add(head, BorderLayout.NORTH);

        lblImage.setHorizontalAlignment(SwingConstants.CENTER);
        lblImage.setVerticalAlignment(SwingConstants.CENTER);
        lblImage.setBackground(new Color(0xFA, 0xFA, 0xFA));
        lblImage.setOpaque(true);
        lblImage.setBorder(BorderFactory.createDashedBorder(BORDER_SOFT, 3f, 4f));
        lblImage.setText("\uD83D\uDCE6");
        lblImage.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 64));
        lblImage.setForeground(new Color(0xBD, 0xBD, 0xBD));
        preview.add(lblImage, BorderLayout.CENTER);
        return preview;
    }

    private JPanel buildForm() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createLineBorder(BORDER_SOFT));

        JPanel head = new JPanel(new BorderLayout());
        head.setBackground(GREEN);
        head.setBorder(new EmptyBorder(8, 14, 8, 14));
        JLabel title = new JLabel("\uD83D\uDCDD  Dados do Produto");
        title.setFont(new Font("Segoe UI Emoji", Font.BOLD, 13));
        title.setForeground(Color.WHITE);
        head.add(title, BorderLayout.WEST);
        card.add(head, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBackground(Color.WHITE);
        grid.setBorder(new EmptyBorder(12, 14, 12, 14));

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        gc.insets = new Insets(4, 6, 4, 6);

        addRow(grid, gc, 0, "Codigo de barras (EAN/GTIN) *", fldBarcode2(), null, null);
        addRow(grid, gc, 1, "Nome do produto *", fldName, "Marca", fldBrand);
        addRow(grid, gc, 2, "Fabricante", fldManufacturer, "Categoria", cmbCategory);
        addRow(grid, gc, 3, "Unidade", cmbUnit, "NCM", fldNcm);
        addRow(grid, gc, 4, "CEST", fldCest, "Prateleira / Local", fldPrateleira);
        addRow(grid, gc, 5, "Preco de custo (R$)", fldCusto, "Preco de venda (R$)", fldVenda);
        addRow(grid, gc, 6, "Estoque inicial", fldEstoque, "Estoque minimo", fldEstoqueMinimo);
        addRow(grid, gc, 7, "Validade (AAAA-MM-DD)", fldValidade, "Observacoes", fldObservacao);

        card.add(grid, BorderLayout.CENTER);
        return card;
    }

    /** Mostra o EAN no form em modo readonly (a edicao acontece no campo de busca). */
    private JComponent fldBarcode2() {
        JTextField mirror = new JTextField();
        mirror.setEditable(false);
        mirror.setFocusable(false);
        mirror.setBackground(GREEN_SOFT);
        mirror.setForeground(GREEN);
        mirror.setFont(new Font("Segoe UI", Font.BOLD, 14));
        mirror.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT),
                new EmptyBorder(6, 10, 6, 10)));
        // Sincroniza valor a partir do campo de busca.
        fldBarcode.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { mirror.setText(fldBarcode.getText().trim()); }
            @Override public void removeUpdate(DocumentEvent e)  { mirror.setText(fldBarcode.getText().trim()); }
            @Override public void changedUpdate(DocumentEvent e) { mirror.setText(fldBarcode.getText().trim()); }
        });
        return mirror;
    }

    private void addRow(JPanel grid, GridBagConstraints gc, int row,
                        String label1, JComponent c1, String label2, JComponent c2) {
        gc.gridy = row;
        gc.gridx = 0; gc.weightx = 0;
        grid.add(formLabel(label1), gc);
        gc.gridx = 1; gc.weightx = 1;
        styleField(c1);
        grid.add(c1, gc);
        if (label2 != null && c2 != null) {
            gc.gridx = 2; gc.weightx = 0;
            grid.add(formLabel(label2), gc);
            gc.gridx = 3; gc.weightx = 1;
            styleField(c2);
            grid.add(c2, gc);
        } else {
            gc.gridx = 2; gc.weightx = 0;
            grid.add(new JLabel(""), gc);
            gc.gridx = 3; gc.weightx = 1;
            grid.add(new JLabel(""), gc);
        }
    }

    private JLabel formLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.BOLD, 11));
        l.setForeground(TEXT_DARK);
        return l;
    }

    private void styleField(JComponent c) {
        if (c instanceof JTextField tf) {
            tf.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            tf.setForeground(TEXT_DARK);
            tf.setBackground(Color.WHITE);
            tf.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER_SOFT),
                    new EmptyBorder(6, 10, 6, 10)));
        } else if (c instanceof JComboBox<?> cb) {
            cb.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            cb.setBackground(Color.WHITE);
        }
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(Color.WHITE);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_SOFT),
                new EmptyBorder(10, 16, 10, 16)));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(btnClear);
        right.add(btnEdit);
        right.add(btnSave);
        right.add(btnClose);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    private JButton primaryButton(String text, Color bg) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color paint = bg;
                if (!isEnabled())             paint = blend(bg, Color.WHITE, 0.55f);
                else if (getModel().isPressed()) paint = bg.darker();
                else if (getModel().isRollover()) paint = blend(bg, Color.BLACK, 0.10f);
                g2.setColor(paint);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Segoe UI Emoji", Font.BOLD, 13));
        b.setBorder(new EmptyBorder(8, 14, 8, 14));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private JButton secondaryButton(String text) {
        JButton b = new JButton(text);
        b.setForeground(TEXT_DARK);
        b.setBackground(Color.WHITE);
        b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT),
                new EmptyBorder(7, 12, 7, 12)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    // -----------------------------------------------------------------
    // Behaviors / event wiring
    // -----------------------------------------------------------------

    private void wireBehavior() {
        // Foco automatico no campo de barras quando a janela aparece.
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                fldBarcode.requestFocusInWindow();
            }
        });
        // Debounce: digitar dispara timer; Enter dispara busca imediata.
        fldBarcode.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { searchDebounce.restart(); }
            @Override public void removeUpdate(DocumentEvent e)  { searchDebounce.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { searchDebounce.restart(); }
        });
        fldBarcode.addActionListener(e -> {
            searchDebounce.stop();
            performSearch();
        });
        btnSearch.addActionListener(e -> performSearch());
        btnSave.addActionListener(e -> saveProduct());
        btnEdit.addActionListener(e -> setFormReadOnly(false));
        btnClear.addActionListener(e -> { clearForm(true); fldBarcode.requestFocusInWindow(); });
        btnClose.addActionListener(e -> dispose());

        // Esc fecha; Ctrl+S salva.
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> btnSave.doClick(),
                KeyStroke.getKeyStroke(KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    // -----------------------------------------------------------------
    // Search workflow
    // -----------------------------------------------------------------

    private void performSearch() {
        String barcode = fldBarcode.getText().trim().replaceAll("[^0-9A-Za-z]", "");
        if (barcode.isEmpty()) {
            setStatus("Digite ou escaneie um codigo de barras.", TEXT_MUTED);
            lblSourceBadge.setVisible(false);
            return;
        }
        if (barcode.length() < 6) {
            setStatus("Codigo muito curto - aguarde a leitura completa do EAN.", TEXT_MUTED);
            return;
        }
        if (currentSearch != null && !currentSearch.isDone()) {
            currentSearch.cancel(true);
        }
        setBusy(true);
        setStatus("Procurando \"" + barcode + "\"...", GREEN_2);
        lblSourceBadge.setVisible(false);

        currentSearch = new SwingWorker<>() {
            @Override
            protected BarcodeLookupService.Outcome doInBackground() {
                return lookupService.lookup(barcode);
            }

            @Override
            protected void done() {
                setBusy(false);
                if (isCancelled()) return;
                try {
                    BarcodeLookupService.Outcome outcome = get();
                    applyOutcome(barcode, outcome);
                } catch (Exception e) {
                    SupportLogger.log("ERROR", "barcode-ui", "Erro consultando", e.getMessage());
                    setStatus("Erro inesperado: " + e.getMessage(), RED);
                }
            }
        };
        currentSearch.execute();
    }

    private void applyOutcome(String barcode, BarcodeLookupService.Outcome outcome) {
        if (outcome.isAlreadyRegistered()) {
            // Caminho 1: produto ja existe -> carrega readonly e oferece "Editar".
            existingProductId = outcome.existingProductId.orElse(null);
            BarcodeLookupResult r = outcome.result.orElse(null);
            if (r != null) fillForm(r);
            setFormReadOnly(true);
            btnSave.setText("\uD83D\uDD04  Atualizar Produto");
            setStatus("Produto ja cadastrado. Clique em Editar para alterar.", ORANGE);
            showBadge("DB", GREEN_SOFT, GREEN);
        } else if (outcome.hasResult()) {
            // Caminho 2: dados vieram de cache ou API.
            existingProductId = null;
            BarcodeLookupResult r = outcome.result.get();
            fillForm(r);
            setFormReadOnly(false);
            btnSave.setText("\uD83D\uDCBE  Salvar Produto");
            String label;
            switch (r.source()) {
                case OPEN_FOOD_FACTS: label = "OpenFoodFacts"; break;
                case COSMOS_BLUESOFT: label = "Cosmos Bluesoft"; break;
                case CACHE: label = "Cache local"; break;
                default: label = r.source().dbValue();
            }
            setStatus("Produto encontrado em " + label + ". Confira os dados e salve.", GREEN);
            showBadge(label, GREEN_SOFT, GREEN);
        } else {
            // Caminho 3: nao encontrado - cadastro manual com EAN preenchido.
            existingProductId = null;
            clearForm(false);
            fldBarcode.setText(barcode);
            setFormReadOnly(false);
            btnSave.setText("\uD83D\uDCBE  Salvar Produto");
            setStatus("Produto nao encontrado nas bases - preencha manualmente.",
                    new Color(0xE6, 0x7E, 0x22));
            showBadge("Manual", new Color(0xFF, 0xF3, 0xE0), new Color(0xE6, 0x7E, 0x22));
        }
        // Mostra warnings (offline, timeout, etc) se houver
        if (!outcome.warnings.isEmpty()) {
            String join = String.join(" | ", outcome.warnings);
            String existing = lblStatus.getText();
            lblStatus.setText(existing + "  -  " + join);
            lblStatus.setToolTipText(join);
        }
    }

    // -----------------------------------------------------------------
    // Form / state helpers
    // -----------------------------------------------------------------

    private void clearForm(boolean clearBarcode) {
        existingProductId = null;
        if (clearBarcode) fldBarcode.setText("");
        fldName.setText("");
        fldBrand.setText("");
        fldManufacturer.setText("");
        cmbCategory.setSelectedIndex(-1);
        cmbUnit.setSelectedItem("un");
        fldNcm.setText("");
        fldCest.setText("");
        fldCusto.setText("0,00");
        fldVenda.setText("0,00");
        fldEstoque.setText("0");
        fldEstoqueMinimo.setText("0");
        fldPrateleira.setText("");
        fldValidade.setText(LocalDate.now().plusMonths(6).toString());
        fldObservacao.setText("");
        lblImage.setIcon(null);
        lblImage.setText("\uD83D\uDCE6");
        setFormReadOnly(false);
        btnSave.setText("\uD83D\uDCBE  Salvar Produto");
        lblSourceBadge.setVisible(false);
    }

    private void fillForm(BarcodeLookupResult r) {
        if (r.barcode() != null) fldBarcode.setText(r.barcode());
        fldName.setText(safe(r.name()));
        fldBrand.setText(safe(r.brand()));
        fldManufacturer.setText(safe(r.manufacturer()));
        if (r.category() != null && !r.category().isBlank()) {
            // Adiciona categoria nova ao combo se necessario.
            ensureCategoryOption(r.category());
            cmbCategory.setSelectedItem(r.category());
        } else {
            cmbCategory.setSelectedIndex(-1);
        }
        if (r.unit() != null && !r.unit().isBlank()) {
            ensureUnitOption(r.unit());
            cmbUnit.setSelectedItem(r.unit());
        }
        fldNcm.setText(safe(r.ncm()));
        fldCest.setText(safe(r.cest()));
        if (r.averagePrice() != null) {
            BigDecimal avg = r.averagePrice().setScale(2, RoundingMode.HALF_UP);
            fldVenda.setText(MONEY_FMT.format(avg));
            // Sugestao: custo = avg / (1 + markup)
            BigDecimal divisor = BigDecimal.valueOf(100 + PRICE_MARKUP_PERCENT)
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            BigDecimal custoSugerido = avg.divide(divisor, 2, RoundingMode.HALF_UP);
            fldCusto.setText(MONEY_FMT.format(custoSugerido));
        }
        loadImageAsync(r.imageUrl());
    }

    private void setFormReadOnly(boolean readOnly) {
        for (JTextField tf : List.of(fldName, fldBrand, fldManufacturer, fldNcm, fldCest,
                fldCusto, fldVenda, fldEstoque, fldEstoqueMinimo,
                fldPrateleira, fldValidade, fldObservacao)) {
            tf.setEditable(!readOnly);
        }
        cmbCategory.setEnabled(!readOnly);
        cmbUnit.setEnabled(!readOnly);
        btnSave.setEnabled(!readOnly || existingProductId != null);
        btnEdit.setVisible(readOnly && existingProductId != null);
    }

    private void setBusy(boolean busy) {
        progressBar.setVisible(busy);
        progressBar.setIndeterminate(busy);
        btnSearch.setEnabled(!busy);
        if (busy) {
            btnSearch.setText("\u23F3  Buscando...");
        } else {
            btnSearch.setText("\uD83D\uDD0D  Buscar");
        }
    }

    private void setStatus(String text, Color color) {
        lblStatus.setText(text);
        lblStatus.setForeground(color);
        lblStatus.setToolTipText(null);
    }

    private void showBadge(String text, Color bg, Color fg) {
        lblSourceBadge.setText(" " + text + " ");
        lblSourceBadge.setBackground(bg);
        lblSourceBadge.setForeground(fg);
        lblSourceBadge.setVisible(true);
    }

    // -----------------------------------------------------------------
    // Image loader (async)
    // -----------------------------------------------------------------

    private void loadImageAsync(String url) {
        if (currentImageWorker != null) currentImageWorker.cancel(true);
        if (url == null || url.isBlank()) {
            lblImage.setIcon(null);
            lblImage.setText("\uD83D\uDCE6");
            return;
        }
        if (url.equalsIgnoreCase(pendingImageUrl)) return;
        pendingImageUrl = url;
        lblImage.setIcon(null);
        lblImage.setText("\u23F3");

        currentImageWorker = new SwingWorker<>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                try {
                    return ImageIO.read(URI.create(url).toURL());
                } catch (Exception e) {
                    return null;
                }
            }
            @Override
            protected void done() {
                if (isCancelled()) return;
                try {
                    BufferedImage img = get();
                    if (img == null) {
                        lblImage.setIcon(null);
                        lblImage.setText("\uD83D\uDCE6");
                        return;
                    }
                    int targetW = lblImage.getWidth() - 10;
                    int targetH = lblImage.getHeight() - 10;
                    if (targetW <= 0) targetW = 200;
                    if (targetH <= 0) targetH = 200;
                    double scale = Math.min(
                            (double) targetW / img.getWidth(),
                            (double) targetH / img.getHeight());
                    int w = Math.max(1, (int) (img.getWidth() * scale));
                    int h = Math.max(1, (int) (img.getHeight() * scale));
                    Image scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
                    lblImage.setText("");
                    lblImage.setIcon(new ImageIcon(scaled));
                } catch (Exception e) {
                    lblImage.setIcon(null);
                    lblImage.setText("\uD83D\uDCE6");
                }
            }
        };
        currentImageWorker.execute();
    }

    // -----------------------------------------------------------------
    // Salvar
    // -----------------------------------------------------------------

    private void saveProduct() {
        try {
            String barcode = fldBarcode.getText().trim().replaceAll("[^0-9A-Za-z]", "");
            String nome = fldName.getText().trim();
            if (barcode.isEmpty()) { warn("Codigo de barras e obrigatorio."); return; }
            if (nome.isEmpty())    { warn("Nome do produto e obrigatorio.");    return; }

            BigDecimal custo = parseMoney(fldCusto.getText());
            BigDecimal venda = parseMoney(fldVenda.getText());
            BigDecimal estoque = parseMoney(fldEstoque.getText());
            BigDecimal estoqueMin = parseMoney(fldEstoqueMinimo.getText());
            String categoria = (String) cmbCategory.getSelectedItem();
            String unidade = (String) cmbUnit.getSelectedItem();
            String marca = fldBrand.getText().trim();
            String fabricante = fldManufacturer.getText().trim();
            String ncm = fldNcm.getText().trim();
            String cest = fldCest.getText().trim();
            String prateleira = fldPrateleira.getText().trim();
            String validade = fldValidade.getText().trim();
            String observacoes = fldObservacao.getText().trim();

            if (existingProductId != null) {
                updateExisting(existingProductId, nome, barcode, marca, fabricante, categoria,
                        unidade, ncm, cest, custo, venda, prateleira, validade, observacoes);
                JOptionPane.showMessageDialog(this,
                        "Produto #" + existingProductId + " atualizado com sucesso!",
                        "Atualizado", JOptionPane.INFORMATION_MESSAGE);
            } else {
                long produtoId = inventoryService.saveProduct(
                        new DesktopInventoryService.ProductDraft(
                                nome,
                                barcode,
                                "",                  // sku auto via codigo_interno
                                emptyToDefault(categoria, "Mercearia"),
                                emptyToDefault(unidade, "un"),
                                custo == null ? BigDecimal.ZERO : custo,
                                venda == null ? BigDecimal.ZERO : venda,
                                estoque == null ? BigDecimal.ZERO : estoque,
                                estoqueMin == null ? BigDecimal.ZERO : estoqueMin,
                                prateleira,
                                validade,
                                observacoes
                        ),
                        operatorId,
                        codigoInternoSupplier.apply(System.nanoTime())
                );
                // Aplica os campos novos (marca/fabricante/ncm/cest/imagem_url) com UPDATE complementar.
                applyExtendedFields(produtoId, marca, fabricante, ncm, cest, pendingImageUrl);
                JOptionPane.showMessageDialog(this,
                        "Produto cadastrado com sucesso (#" + produtoId + ").",
                        "Salvo", JOptionPane.INFORMATION_MESSAGE);
            }
            onProductSaved.run();
            dispose();
        } catch (Exception ex) {
            SupportLogger.log("ERROR", "barcode-ui", "Erro ao salvar produto", ex.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Nao foi possivel salvar:\n" + ex.getMessage(),
                    "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateExisting(long id, String nome, String barcode, String marca, String fabricante,
                                String categoria, String unidade, String ncm, String cest,
                                BigDecimal custo, BigDecimal venda, String prateleira,
                                String validade, String observacoes) throws Exception {
        synchronized (con) {
            try (PreparedStatement ps = con.prepareStatement(
                    "update produtos set nome=?, codigo_barras=?, marca=?, fabricante=?, categoria=?, " +
                    "unidade=?, ncm=?, cest=?, preco_custo=?, preco_venda=?, " +
                    "localizacao=?, validade=?, observacoes=? where id=?")) {
                ps.setString(1, nome);
                ps.setString(2, barcode);
                ps.setString(3, marca);
                ps.setString(4, fabricante);
                ps.setString(5, categoria == null ? "Mercearia" : categoria);
                ps.setString(6, unidade == null ? "un" : unidade);
                ps.setString(7, ncm);
                ps.setString(8, cest);
                ps.setBigDecimal(9, custo == null ? BigDecimal.ZERO : custo);
                ps.setBigDecimal(10, venda == null ? BigDecimal.ZERO : venda);
                ps.setString(11, prateleira);
                ps.setString(12, validade);
                ps.setString(13, observacoes);
                ps.setLong(14, id);
                ps.executeUpdate();
            }
        }
    }

    private void applyExtendedFields(long produtoId, String marca, String fabricante,
                                     String ncm, String cest, String imageUrl) throws Exception {
        synchronized (con) {
            try (PreparedStatement ps = con.prepareStatement(
                    "update produtos set marca=?, fabricante=?, ncm=?, cest=?, imagem_url=?, " +
                    "cadastrado_em=coalesce(cadastrado_em, datetime('now')) where id=?")) {
                ps.setString(1, nullIfBlank(marca));
                ps.setString(2, nullIfBlank(fabricante));
                ps.setString(3, nullIfBlank(ncm));
                ps.setString(4, nullIfBlank(cest));
                ps.setString(5, nullIfBlank(imageUrl));
                ps.setLong(6, produtoId);
                ps.executeUpdate();
            }
        }
    }

    private void loadCategorias() {
        cmbCategory.removeAllItems();
        cmbCategory.addItem(""); // permite vazio
        synchronized (con) {
            try (PreparedStatement ps = con.prepareStatement(
                    "select nome from categorias order by nome")) {
                java.sql.ResultSet rs = ps.executeQuery();
                while (rs.next()) cmbCategory.addItem(rs.getString(1));
            } catch (Exception e) {
                SupportLogger.log("WARN", "barcode-ui", "Falha lendo categorias", e.getMessage());
            }
        }
        cmbCategory.setSelectedIndex(-1);
    }

    private void ensureCategoryOption(String name) {
        for (int i = 0; i < cmbCategory.getItemCount(); i++) {
            if (name.equalsIgnoreCase(cmbCategory.getItemAt(i))) return;
        }
        cmbCategory.addItem(name);
    }

    private void ensureUnitOption(String unit) {
        for (int i = 0; i < cmbUnit.getItemCount(); i++) {
            if (unit.equalsIgnoreCase(cmbUnit.getItemAt(i))) return;
        }
        cmbUnit.addItem(unit);
    }

    // -----------------------------------------------------------------
    // Util
    // -----------------------------------------------------------------

    private static BigDecimal parseMoney(String s) {
        if (s == null) return BigDecimal.ZERO;
        String n = s.trim().replace("R$", "").replace(" ", "")
                .replace(".", "").replace(",", ".");
        if (n.isEmpty()) return BigDecimal.ZERO;
        try { return new BigDecimal(n); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String nullIfBlank(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }
    private static String emptyToDefault(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }

    private void warn(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Atencao", JOptionPane.WARNING_MESSAGE);
    }

    private static Dimension scaledSize(int w, int h) {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int width = Math.min(w, (int) (screen.width * 0.9));
        int height = Math.min(h, (int) (screen.height * 0.9));
        return new Dimension(width, height);
    }

    private static Color blend(Color color, Color base, float weight) {
        float w = Math.max(0f, Math.min(1f, weight));
        int r = Math.round(color.getRed() * (1 - w) + base.getRed() * w);
        int g = Math.round(color.getGreen() * (1 - w) + base.getGreen() * w);
        int b = Math.round(color.getBlue() * (1 - w) + base.getBlue() * w);
        return new Color(r, g, b);
    }
}
