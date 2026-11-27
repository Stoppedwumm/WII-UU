package wiiuu.xcloud;

import javax.swing.*;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/** Short original cinematic Wii-UU ident; uses cached wordmark paths and a bounded Swing timer. */
final class CloudLogoIntro extends JComponent {
    static final int LENGTH_MS=1500;
    private final Shape left,right;
    private final Rectangle2D bounds;
    private final Color accent;
    private final Timer timer;
    private final Runnable finished;
    private long started;
    CloudLogoIntro(Font font,Color accent,Runnable finished){
        this.accent=accent;this.finished=finished;
        FontRenderContext frc=new FontRenderContext(null,true,true);
        Font large=font.deriveFont(Font.BOLD,100f);
        var prefix=large.createGlyphVector(frc,"WII-");
        left=prefix.getOutline();
        right=large.createGlyphVector(frc,"UU").getOutline((float)prefix.getLogicalBounds().getWidth(),0);
        bounds=left.getBounds2D().createUnion(right.getBounds2D());
        setOpaque(true);
        timer=new Timer(40,e->tick());
        timer.setCoalesce(true);
    }
    @Override public void addNotify(){
        super.addNotify();started=System.nanoTime();timer.start();
    }
    @Override public void removeNotify(){timer.stop();super.removeNotify();}
    private void tick(){
        if(elapsed()>=LENGTH_MS){timer.stop();finished.run();}
        else repaint();
    }
    private double elapsed(){return (System.nanoTime()-started)/1e6;}
    private static double clamp(double x){return Math.max(0,Math.min(1,x));}
    private static double ease(double x){return 1-Math.pow(1-clamp(x),3);}
    @Override protected void paintComponent(Graphics graphics){paintAt((Graphics2D)graphics,getWidth(),getHeight(),elapsed());}
    private void paintAt(Graphics2D graphics,int w,int h,double ms){
        Graphics2D g=(Graphics2D)graphics.create();
        try{
            g.setColor(Color.BLACK);g.fillRect(0,0,w,h);
            if(w<=0||h<=0)return;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            // Blue light rays converge into the wordmark, then travel through it.
            float t=(float)clamp(ms/LENGTH_MS);
            float fade=(float)(1-ease((ms-1170)/330));
            float reveal=(float)ease(ms/520);
            float intensity=fade*(float)Math.sin(Math.PI*clamp(ms/1150));
            g.setPaint(new RadialGradientPaint(w/2f,h/2f,Math.max(1f,w*.48f),
                    new float[]{0,.5f,1},new Color[]{new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),(int)(65*intensity)),
                    new Color(8,18,33,(int)(130*fade)),Color.BLACK}));
            g.fillRect(0,0,w,h);
            for(int i=0;i<10;i++){
                double q=(i-4.5)/4.5;
                int x=(int)(w/2.0+q*w*.48*(1-reveal*.48));
                int rayW=Math.max(1,(int)(w*.005*(1+Math.abs(q))));
                int rayH=(int)(h*(.17+.65*(1-reveal)));
                g.setColor(new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),(int)(100*intensity*(1-Math.abs(q)*.5))));
                g.fillRect(x-rayW/2,(h-rayH)/2,rayW,rayH);
            }
            float opacity=fade*(float)ease((ms-100)/310);
            double zoom=1.17-.17*ease(ms/620)+.045*ease((ms-850)/650);
            double scale=Math.min(w*.72/bounds.getWidth(),h*.28/bounds.getHeight())*zoom;
            g.translate(w/2.0,h/2.0);g.scale(scale,scale);g.translate(-bounds.getCenterX(),-bounds.getCenterY());
            g.setComposite(AlphaComposite.SrcOver.derive(opacity));
            // Subtle extrusion gives the letters depth without a 3D renderer.
            for(int i=6;i>=1;i--){
                g.translate(0,i*1.2);g.setColor(new Color(9,30,48));g.fill(left);g.setColor(accent.darker().darker());g.fill(right);g.translate(0,-i*1.2);
            }
            g.setColor(new Color(0xeaf5ff));g.fill(left);g.setColor(accent);g.fill(right);
            Shape clip=g.getClip();
            java.awt.geom.Area wordmark=new java.awt.geom.Area(left);wordmark.add(new java.awt.geom.Area(right));
            g.clip(wordmark);
            float sweep=(float)(bounds.getMinX()-80+(bounds.getWidth()+160)*ease((ms-340)/720));
            g.setPaint(new GradientPaint(sweep-55,0,new Color(255,255,255,0),sweep,0,new Color(255,255,255,190),true));
            g.fill(new Rectangle2D.Double(sweep-55,bounds.getMinY(),110,bounds.getHeight()));
            g.setClip(clip);
        }finally{g.dispose();}
    }
    static void selfTest(){
        CloudLogoIntro logo=new CloudLogoIntro(new Font(Font.SANS_SERIF,Font.BOLD,100),new Color(0x58d4f4),()->{});
        BufferedImage image=new BufferedImage(640,360,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        try{
            logo.paintAt(g,640,360,700);
            int lit=0;
            for(int y=125;y<235;y++)for(int x=100;x<540;x++)if((image.getRGB(x,y)&0xffffff)>0x808080)lit++;
            if(lit<100)throw new AssertionError("Logo wordmark missing");
            logo.paintAt(g,640,360,LENGTH_MS);
            if((image.getRGB(320,180)&0xffffff)!=0)throw new AssertionError("Logo does not fade to black");
        }finally{g.dispose();}
    }
}
