package br.com.mercadotonico.desktop;

import br.com.mercadotonico.core.FixedAdminAuthorizationPassword;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;

/**
 * Dialogo modal para editar todos os dados de um cliente do Fiado.
 *
 * <p>Carrega o registro atual da tabela {@code clientes}, permite alterar
 * nome, CPF, telefone, endereco, limite de credito, observacoes e o
 * status (ativo / bloqueado), e mostra como contexto a divida em fiado
 * em aberto (somente leitura).</p>
 *
 * <p>O salvamento acontece num UPDATE unico; o callback
 * {@link #onSaved} e usado para o frame chamar refresh.</p>
 */
public final class ClienteEditDialog extends JDialog {

    // Paleta espelhada do DesktopApp.
    private static final Color GREEN       = new Color(0x1B, 0x5E, 0x20);
    private static final Color GREEN_2     = new Color(0x2E, 0x7D, 0x32);
    private static final Color GREEN_SOFT  = new Color(0xE8, 0xF5, 0xE9);
    private static final Color RED         = new Color(0xC6, 0x28, 0x28);
    private static final Color BORDER_SOFT = new Color(0xE0, 0xE0, 0xE0);
    private static final Color TEXT_DARK   = new Color(0x21, 0x21, 0x21);
    private static final Color TEXT_MUTED  = new Color(0x61, 0x61, 0x61);
    private static final Color BG          = new Color(0xF5, 0xF5, 0xF5);

    private static final DecimalFormat MONEY_FMT =
            new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("pt-BR")));

    private final Connection con;
    private final long clienteId;
    private final Runnable onSaved;

    private final JTextField fldNome     = new JTextField();
    // CPF com placeholder visual "000.000.000-00" e mascara automatica.
    private final JTextField fldCpf      = DesktopApp.createPlaceholderField("000.000.000-00");
    private final JTextField fldTelefone = new JTextField();
    private final JTextField fldEndereco = new JTextField();
    private final JTextField fldLimite   = new JTextField();
    private final JTextArea  fldObservacoes = new JTextArea(3, 30);
    private final JCheckBox  chkBloqueado = new JCheckBox("Cliente bloqueado (impede novas vendas no convênio)");
    private final JLabel     lblDivida   = new JLabel("R$ 0,00");
    private final JLabel     lblHistorico = new JLabel(" ");
    private final JLabel     lblStatus   = new JLabel(" ");

    /**
     * Limite atual carregado do banco. Usado para detectar se o operador
     * alterou o limite e disparar o prompt de senha de administrador.
     * Se ficar {@code null} (cliente nao encontrado) o save aborta antes.
     */
    private BigDecimal limiteOriginal = null;

    public ClienteEditDialog(Window owner, Connection con, long clienteId, Runnable onSaved) {
        super(owner, "Editar cliente", ModalityType.APPLICATION_MODAL);
        this.con = Objects.requireNonNull(con, "connection");
        this.clienteId = clienteId;
        this.onSaved = onSaved == null ? () -> {} : onSaved;

        // Aplica mascara dinamica de CPF (000.000.000-00) no campo.
        // O placeholder visual ja foi setado na declaracao do JTextField.
        DesktopApp.bindCpfMask(fldCpf);
        // Mascara LIVE de dinheiro no limite de credito - estilo calculadora.
        DesktopApp.bindMoneyMaskLive(fldLimite);

        buildUi();
        loadFromDatabase();

        setSize(scaledSize(620, 600));
        setLocationRelativeTo(owner);
    }

    // -----------------------------------------------------------------
    // UI
    // -----------------------------------------------------------------

    private void buildUi() {
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout());

        add(buildHeader(), BorderLayout.NORTH);
        add(buildForm(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        // Atalhos de teclado.
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> save(),
                KeyStroke.getKeyStroke(KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
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

        JLabel title = new JLabel("\u270F\uFE0F  Editar Cliente");
        title.setFont(new Font("Segoe UI Emoji", Font.BOLD, 18));
        title.setForeground(Color.WHITE);

        JLabel sub = new JLabel("Atualize os dados pessoais e o limite de credito.");
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        sub.setForeground(new Color(0xC8, 0xE6, 0xC9));

        JPanel block = new JPanel();
        block.setOpaque(false);
        block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        block.add(title);
        block.add(Box.createVerticalStrut(2));
        block.add(sub);
        header.add(block, BorderLayout.WEST);
        return header;
    }

    private JPanel buildForm() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                new EmptyBorder(12, 16, 0, 16),
                BorderFactory.createLineBorder(BORDER_SOFT)));

        JPanel sectionHead = new JPanel(new BorderLayout());
        sectionHead.setBackground(GREEN);
        sectionHead.setBorder(new EmptyBorder(8, 14, 8, 14));
        JLabel sh = new JLabel("\uD83D\uDC64  Dados pessoais");
        sh.setFont(new Font("Segoe UI Emoji", Font.BOLD, 13));
        sh.setForeground(Color.WHITE);
        sectionHead.add(sh, BorderLayout.WEST);
        card.add(sectionHead, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBackground(Color.WHITE);
        grid.setBorder(new EmptyBorder(14, 16, 14, 16));

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(4, 6, 4, 6);

        addRow(grid, gc, 0, "Nome completo *", fldNome, "CPF", fldCpf);
        addRow(grid, gc, 1, "Telefone", fldTelefone, "Limite de credito (R$)", fldLimite);

        // Endereco em linha cheia (4 colunas).
        gc.gridy = 2;
        gc.gridx = 0; gc.weightx = 0;
        grid.add(formLabel("Endereco completo"), gc);
        gc.gridx = 1; gc.gridwidth = 3; gc.weightx = 1;
        styleField(fldEndereco);
        grid.add(fldEndereco, gc);
        gc.gridwidth = 1;

        // Observacoes em area multi-linha.
        gc.gridy = 3;
        gc.gridx = 0; gc.weightx = 0;
        grid.add(formLabel("Observacoes"), gc);
        gc.gridx = 1; gc.gridwidth = 3; gc.weightx = 1; gc.fill = GridBagConstraints.BOTH;
        gc.weighty = 1.0;
        fldObservacoes.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        fldObservacoes.setLineWrap(true);
        fldObservacoes.setWrapStyleWord(true);
        JScrollPane obsScroll = new JScrollPane(fldObservacoes);
        obsScroll.setBorder(BorderFactory.createLineBorder(BORDER_SOFT));
        obsScroll.setPreferredSize(new Dimension(0, 70));
        grid.add(obsScroll, gc);
        gc.gridwidth = 1; gc.weighty = 0; gc.fill = GridBagConstraints.HORIZONTAL;

        // Status (checkbox bloqueado).
        gc.gridy = 4;
        gc.gridx = 0; gc.weightx = 0;
        grid.add(formLabel("Status"), gc);
        gc.gridx = 1; gc.gridwidth = 3; gc.weightx = 1;
        chkBloqueado.setOpaque(false);
        chkBloqueado.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        chkBloqueado.setForeground(TEXT_DARK);
        grid.add(chkBloqueado, gc);
        gc.gridwidth = 1;

        // Bloco financeiro (read-only) com a divida atual.
        gc.gridy = 5;
        gc.gridx = 0; gc.gridwidth = 4; gc.weightx = 1;
        gc.insets = new Insets(12, 6, 4, 6);
        grid.add(buildFinanceBlock(), gc);
        gc.gridwidth = 1;
        gc.insets = new Insets(4, 6, 4, 6);

        card.add(grid, BorderLayout.CENTER);

        // Status / mensagem na base do card.
        lblStatus.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblStatus.setForeground(TEXT_MUTED);
        lblStatus.setBorder(new EmptyBorder(8, 16, 12, 16));
        card.add(lblStatus, BorderLayout.SOUTH);

        JPanel container = new JPanel(new BorderLayout());
        container.setBackground(BG);
        container.add(card, BorderLayout.CENTER);
        return container;
    }

    /** Caixa amarela com a dívida atual + histórico curto. */
    private JComponent buildFinanceBlock() {
        JPanel box = new JPanel(new GridBagLayout());
        box.setBackground(new Color(0xFF, 0xF8, 0xE1));
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xFB, 0xC0, 0x2D), 1),
                new EmptyBorder(10, 14, 10, 14)));

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0;
        gc.gridx = 0; gc.weightx = 0;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(0, 0, 6, 12);

        JLabel t = new JLabel("\uD83D\uDCB0  Convênio em aberto");
        t.setFont(new Font("Segoe UI Emoji", Font.BOLD, 12));
        t.setForeground(new Color(0x5D, 0x40, 0x37));
        box.add(t, gc);

        gc.gridx = 1; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        lblDivida.setFont(new Font("Segoe UI", Font.BOLD, 16));
        lblDivida.setForeground(new Color(0xC6, 0x28, 0x28));
        box.add(lblDivida, gc);

        gc.gridy = 1; gc.gridx = 0; gc.gridwidth = 2; gc.weightx = 1;
        gc.insets = new Insets(0, 0, 0, 0);
        lblHistorico.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lblHistorico.setForeground(new Color(0x5D, 0x40, 0x37));
        box.add(lblHistorico, gc);

        return box;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(Color.WHITE);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_SOFT),
                new EmptyBorder(10, 16, 10, 16)));

        JButton btnSave = primaryButton("\uD83D\uDCBE  Salvar alteracoes", GREEN_2);
        btnSave.addActionListener(e -> save());

        JButton btnCancel = primaryButton("Cancelar", RED);
        btnCancel.addActionListener(e -> dispose());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(btnCancel);
        right.add(btnSave);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    // -----------------------------------------------------------------
    // Helpers de layout
    // -----------------------------------------------------------------

    private void addRow(JPanel grid, GridBagConstraints gc, int row,
                        String label1, JComponent c1, String label2, JComponent c2) {
        gc.gridy = row;
        gc.gridx = 0; gc.weightx = 0;
        grid.add(formLabel(label1), gc);
        gc.gridx = 1; gc.weightx = 1;
        styleField(c1);
        grid.add(c1, gc);
        gc.gridx = 2; gc.weightx = 0;
        grid.add(formLabel(label2), gc);
        gc.gridx = 3; gc.weightx = 1;
        styleField(c2);
        grid.add(c2, gc);
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
        }
    }

    private JButton primaryButton(String text, Color bg) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color paint = bg;
                if (!isEnabled())                  paint = blend(bg, Color.WHITE, 0.55f);
                else if (getModel().isPressed())   paint = bg.darker();
                else if (getModel().isRollover())  paint = blend(bg, Color.BLACK, 0.10f);
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

    // -----------------------------------------------------------------
    // Database
    // -----------------------------------------------------------------

    private void loadFromDatabase() {
        try (PreparedStatement ps = con.prepareStatement(
                "select nome, cpf, telefone, endereco, limite_credito, " +
                "observacoes, bloqueado from clientes where id = ?")) {
            ps.setLong(1, clienteId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    lblStatus.setText("Cliente nao encontrado (#" + clienteId + ").");
                    lblStatus.setForeground(RED);
                    return;
                }
                fldNome.setText(safe(rs.getString("nome")));
                fldCpf.setText(safe(rs.getString("cpf")));
                fldTelefone.setText(safe(rs.getString("telefone")));
                fldEndereco.setText(safe(rs.getString("endereco")));
                BigDecimal limite = rs.getBigDecimal("limite_credito");
                if (limite == null) limite = BigDecimal.ZERO;
                limiteOriginal = limite.setScale(2, java.math.RoundingMode.HALF_UP);
                fldLimite.setText(MONEY_FMT.format(limite));
                fldObservacoes.setText(safe(rs.getString("observacoes")));
                chkBloqueado.setSelected(rs.getInt("bloqueado") == 1);
            }
        } catch (Exception e) {
            lblStatus.setText("Erro lendo cliente: " + e.getMessage());
            lblStatus.setForeground(RED);
            return;
        }
        loadFinanceSummary();
        lblStatus.setText("Cliente #" + clienteId + " carregado. Edite os campos e salve.");
        lblStatus.setForeground(GREEN);
    }

    private void loadFinanceSummary() {
        try (PreparedStatement ps = con.prepareStatement("""
                select coalesce(sum(valor - valor_pago), 0) as aberto,
                       count(*) as qtd_aberto
                  from fiado
                 where cliente_id = ? and status = 'ABERTO'
                """)) {
            ps.setLong(1, clienteId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal aberto = rs.getBigDecimal("aberto");
                    int qtd = rs.getInt("qtd_aberto");
                    if (aberto == null) aberto = BigDecimal.ZERO;
                    lblDivida.setText("R$ " + MONEY_FMT.format(aberto));
                    if (qtd == 0) {
                        lblHistorico.setText("Nenhuma venda no convênio pendente.");
                    } else {
                        lblHistorico.setText(qtd + " venda" + (qtd == 1 ? "" : "s")
                                + " em aberto. Para quitar, use o botão Baixa na aba Convênio.");
                    }
                }
            }
        } catch (Exception ignored) {
            // Falha silenciosa: bloco financeiro e contextual, nao bloqueia salvar.
        }
    }

    /**
     * Mostra um dialogo modal pedindo a senha do administrador para
     * confirmar a alteracao do limite. Mostra os dois valores (de/para)
     * para o operador conferir antes de digitar a senha.
     *
     * @return {@code true} se a senha conferiu, {@code false} caso
     *         contrario (operador cancelou ou errou a senha).
     */
    private boolean solicitarSenhaParaAlterarLimite(BigDecimal antigo, BigDecimal novo) {
        JLabel info = new JLabel(
                "<html>O <b>limite de credito</b> esta sendo alterado.<br>"
                + "De: <b>R$ " + MONEY_FMT.format(antigo) + "</b>"
                + "  &rarr;  Para: <b>R$ " + MONEY_FMT.format(novo) + "</b><br>"
                + "<br>Digite a senha de <b>autorizacao</b> do estabelecimento ou a senha de login do "
                + "<b>Admin</b> / <b>Gerente</b> para confirmar.</html>");
        info.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        JPasswordField senha = new JPasswordField();
        senha.setFont(new Font("Segoe UI", Font.PLAIN, 14));

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.add(info, BorderLayout.NORTH);
        panel.add(senha, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(420, 130));

        // Foca no campo de senha quando o dialogo aparecer.
        SwingUtilities.invokeLater(senha::requestFocusInWindow);

        int opt = JOptionPane.showConfirmDialog(
                this, panel,
                "Senha de administrador",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (opt != JOptionPane.OK_OPTION) {
            return false;
        }
        String digitada = new String(senha.getPassword());
        if (!FixedAdminAuthorizationPassword.PLAINTEXT.equals(digitada)) {
            JOptionPane.showMessageDialog(this,
                    "Senha incorreta. A alteracao do limite NAO foi salva.",
                    "Senha invalida",
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
        return true;
    }

    private void save() {
        String nome = fldNome.getText() == null ? "" : fldNome.getText().trim();
        if (nome.isEmpty()) {
            lblStatus.setForeground(RED);
            lblStatus.setText("O nome do cliente e obrigatorio.");
            fldNome.requestFocusInWindow();
            return;
        }

        // Valida CPF se preenchido (deixar em branco e permitido).
        String cpfBruto = fldCpf.getText() == null ? "" : fldCpf.getText().trim();
        if (!cpfBruto.isEmpty() && !DesktopApp.isValidCpf(cpfBruto)) {
            lblStatus.setForeground(RED);
            lblStatus.setText("CPF invalido. Verifique os numeros ou deixe o campo em branco.");
            fldCpf.requestFocusInWindow();
            return;
        }

        BigDecimal limite;
        try {
            limite = parseMoney(fldLimite.getText());
            if (limite.signum() < 0) throw new IllegalArgumentException();
            limite = limite.setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (Exception e) {
            lblStatus.setForeground(RED);
            lblStatus.setText("Limite de credito invalido. Use formato 0,00.");
            fldLimite.requestFocusInWindow();
            return;
        }

        // SENHA DE ADMINISTRADOR PARA ALTERAR O LIMITE.
        // Se o operador mudou o valor de "Limite de credito (R$)", o sistema
        // pede uma senha antes de salvar - politica do dono pra evitar que
        // qualquer operador suba o limite de fiado de um cliente sem
        // autorizacao. A senha fixa padrao e FixedAdminAuthorizationPassword.PLAINTEXT.
        if (limiteOriginal != null && limite.compareTo(limiteOriginal) != 0) {
            if (!solicitarSenhaParaAlterarLimite(limiteOriginal, limite)) {
                lblStatus.setForeground(RED);
                lblStatus.setText("Alteracao de limite cancelada (senha nao confirmada).");
                fldLimite.requestFocusInWindow();
                return;
            }
        }

        // CPF salvo so com digitos (sem pontos / traco) pra padronizar.
        String cpfBrutoSalvar = nullIfBlank(fldCpf.getText());
        String cpf = cpfBrutoSalvar == null ? null : cpfBrutoSalvar.replaceAll("\\D", "");
        if (cpf != null && cpf.isEmpty()) cpf = null;
        String telefone = nullIfBlank(fldTelefone.getText());
        String endereco = nullIfBlank(fldEndereco.getText());
        String observacoes = nullIfBlank(fldObservacoes.getText());
        int bloqueado = chkBloqueado.isSelected() ? 1 : 0;

        try (PreparedStatement ps = con.prepareStatement(
                "update clientes set nome=?, cpf=?, telefone=?, endereco=?, " +
                "limite_credito=?, observacoes=?, bloqueado=? where id=?")) {
            ps.setString(1, nome);
            if (cpf == null) ps.setNull(2, java.sql.Types.VARCHAR); else ps.setString(2, cpf);
            ps.setString(3, telefone);
            ps.setString(4, endereco);
            ps.setBigDecimal(5, limite);
            ps.setString(6, observacoes);
            ps.setInt(7, bloqueado);
            ps.setLong(8, clienteId);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            String msg = e.getMessage();
            if (msg != null && msg.toLowerCase(Locale.ROOT).contains("unique")
                    && msg.toLowerCase(Locale.ROOT).contains("cpf")) {
                lblStatus.setForeground(RED);
                lblStatus.setText("Ja existe outro cliente com este CPF.");
                fldCpf.requestFocusInWindow();
                return;
            }
            lblStatus.setForeground(RED);
            lblStatus.setText("Erro ao salvar: " + msg);
            return;
        } catch (Exception e) {
            lblStatus.setForeground(RED);
            lblStatus.setText("Erro inesperado: " + e.getMessage());
            return;
        }

        JOptionPane.showMessageDialog(this,
                "Cliente atualizado com sucesso.",
                "Salvo", JOptionPane.INFORMATION_MESSAGE);
        onSaved.run();
        dispose();
    }

    // -----------------------------------------------------------------
    // Util
    // -----------------------------------------------------------------

    private static String safe(String s) { return s == null ? "" : s; }

    private static String nullIfBlank(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal parseMoney(String s) {
        if (s == null) return BigDecimal.ZERO;
        String n = s.trim().replace("R$", "").replace(" ", "")
                .replace(".", "").replace(",", ".");
        if (n.isEmpty()) return BigDecimal.ZERO;
        return new BigDecimal(n);
    }

    private static Dimension scaledSize(int w, int h) {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int width = Math.min(w, (int) (screen.width * 0.9));
        int height = Math.min(h, (int) (screen.height * 0.9));
        return new Dimension(width, height);
    }

    private static Color blend(Color color, Color base, float weight) {
        float wt = Math.max(0f, Math.min(1f, weight));
        int r = Math.round(color.getRed() * (1 - wt) + base.getRed() * wt);
        int g = Math.round(color.getGreen() * (1 - wt) + base.getGreen() * wt);
        int b = Math.round(color.getBlue() * (1 - wt) + base.getBlue() * wt);
        return new Color(r, g, b);
    }
}
