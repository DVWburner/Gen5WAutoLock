import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class GenIcon {
    public static void main(String[] args) throws Exception {
        String baseRes = args.length > 0 ? args[0] : "packages/gen5w.autolock/app_src/res";
        int[] sizes = {48, 72, 96, 144, 192};
        String[] dirs = {"mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"};

        for (int i = 0; i < sizes.length; i++) {
            File dir = new File(baseRes, dirs[i]);
            dir.mkdirs();
            BufferedImage img = renderIcon(sizes[i]);
            File out = new File(dir, "ic_launcher.png");
            ImageIO.write(img, "PNG", out);
            System.out.println("Wrote " + out.getPath() + " (" + sizes[i] + "x" + sizes[i] + ")");
        }
    }

    private static BufferedImage renderIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        float s = size / 100f;

        // Background rounded square badge
        float pad = 4f * s;
        float arc = 24f * s;
        RoundRectangle2D bg = new RoundRectangle2D.Float(pad, pad, size - 2 * pad, size - 2 * pad, arc, arc);
        GradientPaint bgGrad = new GradientPaint(
                0, pad, new Color(0x14, 0x1E, 0x30),
                0, size - pad, new Color(0x0A, 0x0F, 0x1A));
        g.setPaint(bgGrad);
        g.fill(bg);

        // Subtle cyan border
        g.setStroke(new BasicStroke(2.5f * s));
        g.setColor(new Color(0x38, 0xBD, 0xF8, 180));
        g.draw(bg);

        // Padlock shackle (top arch)
        float shackleW = 30f * s;
        float shackleH = 26f * s;
        float shackleX = (size - shackleW) / 2f;
        float shackleY = 17f * s;
        g.setStroke(new BasicStroke(6.5f * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0x7D, 0xD3, 0xFC));
        g.draw(new Arc2D.Float(shackleX, shackleY, shackleW, shackleH * 1.4f, 0, 180, Arc2D.OPEN));

        // Shackle vertical legs down into lock body
        float legTop = shackleY + shackleH * 0.7f;
        float bodyY = 39f * s;
        g.draw(new Line2D.Float(shackleX, legTop, shackleX, bodyY + 2f * s));
        g.draw(new Line2D.Float(shackleX + shackleW, legTop, shackleX + shackleW, bodyY + 2f * s));

        // Padlock body
        float bodyW = 52f * s;
        float bodyH = 44f * s;
        float bodyX = (size - bodyW) / 2f;
        RoundRectangle2D body = new RoundRectangle2D.Float(bodyX, bodyY, bodyW, bodyH, 10f * s, 10f * s);
        GradientPaint bodyGrad = new GradientPaint(
                bodyX, bodyY, new Color(0x1E, 0x3A, 0x5F),
                bodyX, bodyY + bodyH, new Color(0x0F, 0x25, 0x42));
        g.setPaint(bodyGrad);
        g.fill(body);

        g.setStroke(new BasicStroke(2.2f * s));
        g.setColor(new Color(0x38, 0xBD, 0xF8));
        g.draw(body);

        // Clock dial centered inside lock body
        float cx = size / 2f;
        float cy = bodyY + bodyH / 2f;
        float r = 14.5f * s;

        g.setColor(new Color(0x0B, 0x13, 0x20));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));

        g.setStroke(new BasicStroke(2.2f * s));
        g.setColor(new Color(0x22, 0xD3, 0xEE));
        g.draw(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));

        // 12, 3, 6, 9 tick marks
        g.setStroke(new BasicStroke(1.8f * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0x94, 0xA3, 0xB8));
        for (int a = 0; a < 360; a += 90) {
            double rad = Math.toRadians(a);
            float x1 = cx + (float) Math.cos(rad) * (r - 3.2f * s);
            float y1 = cy + (float) Math.sin(rad) * (r - 3.2f * s);
            float x2 = cx + (float) Math.cos(rad) * (r - 1.2f * s);
            float y2 = cy + (float) Math.sin(rad) * (r - 1.2f * s);
            g.draw(new Line2D.Float(x1, y1, x2, y2));
        }

        // Clock hands (12 o'clock and ~2 o'clock) in bright amber/white
        g.setStroke(new BasicStroke(2.4f * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.WHITE);
        g.draw(new Line2D.Float(cx, cy, cx, cy - r * 0.62f)); // minute hand up
        g.setColor(new Color(0xFB, 0xBF, 0x24));
        g.draw(new Line2D.Float(cx, cy, cx + r * 0.48f, cy + r * 0.25f)); // second/hour hand

        // Center pivot dot
        float dotR = 2.0f * s;
        g.setColor(new Color(0xFB, 0xBF, 0x24));
        g.fill(new Ellipse2D.Float(cx - dotR, cy - dotR, 2 * dotR, 2 * dotR));

        g.dispose();
        return img;
    }
}
