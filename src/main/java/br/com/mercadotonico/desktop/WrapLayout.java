package br.com.mercadotonico.desktop;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * {@link FlowLayout} que recalcula a altura preferida quebrando linhas quando
 * o conteudo nao cabe na largura disponivel.
 *
 * <p>O {@code FlowLayout} padrao do Swing sempre devolve a altura como se
 * tudo coubesse numa unica linha. Em telas pequenas (notebook compacto), isso
 * faz com que botoes ou chips sumam para fora da janela. Esta variante mede
 * a largura disponivel do container pai e quebra na proxima linha sempre que
 * passar do limite, retornando a altura real ocupada.</p>
 *
 * <p>Adaptado da implementacao classica de Rob Camick (PD).</p>
 */
public final class WrapLayout extends FlowLayout {

    public WrapLayout() { super(); }

    public WrapLayout(int align) { super(align); }

    public WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false);
        minimum.width -= (getHgap() + 1);
        return minimum;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = target.getSize().width;
            Container container = target;
            while (container.getSize().width == 0 && container.getParent() != null) {
                container = container.getParent();
            }
            targetWidth = container.getSize().width;
            if (targetWidth == 0) {
                targetWidth = Integer.MAX_VALUE;
            }

            int hgap = getHgap();
            int vgap = getVgap();
            java.awt.Insets insets = target.getInsets();
            int horizontalInsets = insets.left + insets.right + (hgap * 2);
            int maxWidth = targetWidth - horizontalInsets;

            Dimension dim = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;

            int n = target.getComponentCount();
            for (int i = 0; i < n; i++) {
                Component m = target.getComponent(i);
                if (!m.isVisible()) continue;
                Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();

                if (rowWidth + d.width > maxWidth) {
                    addRow(dim, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth != 0) rowWidth += hgap;
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            addRow(dim, rowWidth, rowHeight);

            dim.width += horizontalInsets;
            dim.height += insets.top + insets.bottom + vgap * 2;

            // Em containers Scroll/Viewport, devolver largura inteira evita
            // expansao infinita lateral.
            java.awt.Container scrollPane = javax.swing.SwingUtilities.getAncestorOfClass(
                    javax.swing.JScrollPane.class, target);
            if (scrollPane != null && target.isValid()) {
                dim.width -= (hgap + 1);
            }
            return dim;
        }
    }

    private void addRow(Dimension dim, int rowWidth, int rowHeight) {
        dim.width = Math.max(dim.width, rowWidth);
        if (dim.height > 0) dim.height += getVgap();
        dim.height += rowHeight;
    }
}
