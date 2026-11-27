package wiiuu.xcloud;

import javax.swing.*;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/** Short original cinematic Wii-UU ident; uses cached wordmark paths and a bounded Swing timer. */
final class CloudLogoIntro extends JComponent {
    static final int LENGTH_MS=5600;
    private static final int LOGO_START=3100;
    private final java.util.function.BiConsumer<Graphics2D,float[]> mascot;
    private final Shape left,right;
    private final Rectangle2D bounds;
    private final Color accent;
    private final Timer timer;
    private final Runnable finished;
    private long started;
    CloudLogoIntro(Font font,Color accent,Runnable finished){
        this(font,accent,finished,(g,p)->{});
    }
    CloudLogoIntro(Font font,Color accent,Runnable finished,java.util.function.BiConsumer<Graphics2D,float[]> mascot){
        this.accent=accent;this.finished=finished;this.mascot=mascot;
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
        if(ms<LOGO_START){
            mascotScene(graphics,w,h,ms);
        }else{
            double logoMs=ms-LOGO_START;
            paintLogo(graphics,w,h,logoMs);
            if(logoMs<950){
                Graphics2D g=(Graphics2D)graphics.create();
                try{
                    float fade=(float)(1-ease((logoMs-550)/400));
                    g.setComposite(AlphaComposite.SrcOver.derive(fade));
                    float u=Math.min(w/640f,h/360f);
                    float x=(float)(w*.5+(w*.4)*ease(logoMs/950));
                    mascot.accept(g,new float[]{x,h*.87f,29*u,(float)Math.sin(logoMs/70),w*.5f,h*.42f});
                }finally{g.dispose();}
            }
        }
    }
    private void mascotScene(Graphics2D graphics,int w,int h,double ms){
        Graphics2D g=(Graphics2D)graphics.create();
        try{
            g.setColor(Color.BLACK);g.fillRect(0,0,w,h);
            if(w<=0||h<=0)return;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            float u=Math.min(w/640f,h/360f);
            g.translate((w-640*u)/2,(h-360*u)/2);g.scale(u,u);
            float light=(float)ease(ms/850);
            g.setPaint(new RadialGradientPaint(320,192,340,new float[]{0,.5f,1},
                    new Color[]{new Color(12,36,59),new Color(5,15,28),Color.BLACK}));
            g.fillRect(0,0,640,360);
            // Stage floor and reflective perspective lines.
            g.setColor(new Color(8,20,33));g.fillRect(0,256,640,104);
            for(int i=-5;i<=5;i++){
                g.setColor(new Color(47,116,157,(int)(70*light)));
                g.drawLine(320+i*16,256,320+i*100,360);
            }
            for(int y=260;y<360;y+=(y-250)/3+4){g.setColor(new Color(47,116,157,(int)(40*light)));g.drawLine(0,y,640,y);}
            float x=(float)(-80+400*ease(ms/1250));
            double jump=ms<1950||ms>2510?0:Math.sin(Math.PI*(ms-1950)/560);
            float foot=278-(float)(jump*46);
            float pulse=(float)(ease((ms-2180)/380));
            // A small power core wakes up when the mascot lands.
            float glow=12+42*pulse;
            g.setPaint(new RadialGradientPaint(320,116,Math.max(1,glow),new float[]{0,.24f,1},
                    new Color[]{new Color(210,245,255,(int)(220*pulse)),new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),(int)(190*pulse)),new Color(0,0,0,0)}));
            g.fill(new java.awt.geom.Ellipse2D.Float(320-glow,116-glow,2*glow,2*glow));
            for(int i=0;i<3;i++){
                float r=15+i*16+(float)(ms%800)/800*10;
                g.setColor(new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),(int)(110*pulse*(1-i*.2))));
                g.draw(new java.awt.geom.Ellipse2D.Float(320-r,116-r,r*2,r*2));
            }
            g.setColor(new Color(0,0,0,95));g.fillOval((int)x-50,269,100,11);
            float stride=ms<1250?(float)Math.sin(ms/65):0;
            mascot.accept(g,new float[]{x,foot,48,stride,320,116});
            if(ms>2560){
                float p=(float)ease((ms-2560)/540);
                g.setColor(new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),(int)(160*p)));
                for(int i=0;i<12;i++){
                    double a=i*Math.PI/6;float r=20+230*p;
                    g.draw(new java.awt.geom.Line2D.Double(320+Math.cos(a)*r*.75,116+Math.sin(a)*r*.75,320+Math.cos(a)*r,116+Math.sin(a)*r));
                }
                g.setColor(new Color(0,0,0,(int)(255*p)));g.fillRect(0,0,640,360);
            }
        }finally{g.dispose();}
    }
    private void paintLogo(Graphics2D graphics,int w,int h,double ms){
        Graphics2D g=(Graphics2D)graphics.create();
        try{
            g.setColor(Color.BLACK);g.fillRect(0,0,w,h);
            if(w<=0||h<=0||ms>=2500)return;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            double arrive=ease((ms-450)/650),exit=ease((ms-2050)/450);
            float fade=(float)(1-exit),energy=(float)(Math.sin(Math.PI*clamp(ms/1650))*fade);
            g.setPaint(new RadialGradientPaint(w*.5f,h*.5f,Math.max(1,w*.54f),new float[]{0,.44f,1},
                    new Color[]{new Color(12,34,57),new Color(3,12,24),Color.BLACK}));
            g.fillRect(0,0,w,h);
            // A perspective tunnel of luminous ribbons. The vanishing point opens into the mark.
            for(int i=0;i<24;i++){
                double angle=i*Math.PI*2/24+.12*Math.sin(ms/600);
                double phase=(ms/1050+i*.173)%1, radius=.08+phase*phase*.88;
                double x=Math.cos(angle)*w*.78,y=Math.sin(angle)*h*.78;
                double inner=Math.max(.035,radius-.12-.12*phase);
                int a=(int)(energy*(35+145*phase));
                g.setColor(new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),a/4));
                g.setStroke(new BasicStroke((float)(2+phase*9),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                g.draw(new java.awt.geom.Line2D.Double(w*.5+x*inner,h*.5+y*inner,w*.5+x*radius,h*.5+y*radius));
                g.setColor(new Color(145,225,255,a));g.setStroke(new BasicStroke((float)(.7+phase*1.8)));
                g.draw(new java.awt.geom.Line2D.Double(w*.5+x*inner,h*.5+y*inner,w*.5+x*radius,h*.5+y*radius));
            }
            // A horizontal impact flare, confined to a narrow band rather than a full-screen flash.
            double impact=Math.exp(-Math.pow((ms-1090)/170,2));
            float line=(float)(fade*(.13+.87*impact)*ease((ms-600)/400));
            int span=(int)(w*(.15+.78*impact)*arrive);
            if(span>0){
                g.setPaint(new GradientPaint(0,h*.5f-12,new Color(80,200,255,0),0,h*.5f,new Color(140,225,255,(int)(155*line)),true));
                g.fillRect((w-span)/2,h/2-12,span,24);
                g.setColor(new Color(205,244,255,(int)(220*line)));g.fillRect((w-span)/2,h/2,span,1);
            }
            double zoom=1.22-.22*arrive+.55*exit;
            double scale=Math.min(w*.70/bounds.getWidth(),h*.26/bounds.getHeight())*zoom;
            g.translate(w/2.0,h/2.0);g.scale(scale,scale);g.translate(-bounds.getCenterX(),-bounds.getCenterY());
            // Two separately moving halves form the wordmark, with a layered metal extrusion.
            double slide=95*(1-arrive);
            for(int side=0;side<2;side++){
                Shape glyph=side==0?left:right;
                Graphics2D l=(Graphics2D)g.create();
                try{
                    double p=ease((ms-220-side*100)/500);
                    l.translate((side==0?-1:1)*slide,(side==0?-1:1)*14*(1-arrive));
                    l.shear((side==0?-.16:.16)*(1-arrive),0);
                    l.setComposite(AlphaComposite.SrcOver.derive((float)(p*fade)));
                    Color face=side==0?new Color(0xeaf5ff):accent;
                    for(int d=12;d>=1;d--){
                        l.translate(d*.48,d*.72);l.setColor(side==0?new Color(22+d,40+d,60+d):accent.darker().darker());l.fill(glyph);l.translate(-d*.48,-d*.72);
                    }
                    l.setPaint(new GradientPaint(0,(float)bounds.getMinY(),face.brighter(),0,(float)bounds.getMaxY(),face.darker()));l.fill(glyph);
                    l.setStroke(new BasicStroke(.65f));l.setColor(new Color(220,246,255,155));l.draw(glyph);
                    l.clip(glyph);
                    float sweep=(float)(bounds.getMinX()-100+(bounds.getWidth()+200)*ease((ms-980)/850));
                    l.setPaint(new GradientPaint(sweep-48,0,new Color(255,255,255,0),sweep,0,new Color(255,255,255,215),true));
                    l.fill(new Rectangle2D.Double(sweep-48,bounds.getMinY(),96,bounds.getHeight()));
                }finally{l.dispose();}
            }
        }finally{g.dispose();}
        // Fade the complete scene, including the tunnel, to black.
        if(ms>2050){Graphics2D end=(Graphics2D)graphics.create();try{end.setColor(new Color(0,0,0,(int)(255*ease((ms-2050)/450))));end.fillRect(0,0,w,h);}finally{end.dispose();}}
    }
    static void selfTest(){
        java.util.concurrent.atomic.AtomicInteger mascotDraws=new java.util.concurrent.atomic.AtomicInteger();
        CloudLogoIntro logo=new CloudLogoIntro(new Font(Font.SANS_SERIF,Font.BOLD,100),new Color(0x58d4f4),()->{},(g,p)->mascotDraws.incrementAndGet());
        BufferedImage image=new BufferedImage(640,360,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        try{
            logo.paintAt(g,640,360,1100);
            logo.paintAt(g,640,360,LOGO_START+300);
            if(mascotDraws.get()!=2)throw new AssertionError("Mascot missing from cinematic");
            logo.paintAt(g,640,360,LOGO_START+1450);
            int lit=0;
            for(int y=125;y<235;y++)for(int x=100;x<540;x++)if((image.getRGB(x,y)&0xffffff)>0x808080)lit++;
            if(lit<100)throw new AssertionError("Logo wordmark missing");
            logo.paintAt(g,640,360,LENGTH_MS);
            if((image.getRGB(320,180)&0xffffff)!=0)throw new AssertionError("Logo does not fade to black");
        }finally{g.dispose();}
    }
}
