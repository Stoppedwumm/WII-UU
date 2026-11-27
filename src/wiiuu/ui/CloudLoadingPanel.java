package wiiuu.ui;

import javax.swing.*;
import java.awt.*;

/** Lightweight idle animation using the same mascot as the main Wii-UU menu. */
final class CloudLoadingPanel extends JComponent {
    private final AnimatedMascot mascot;
    private final Timer timer;
    private final long started=System.nanoTime();
    private String status="Preparing Java RTC...";
    private long lastHop=-1;
    CloudLoadingPanel(AnimatedMascot mascot){
        this.mascot=mascot;
        setOpaque(true);
        // Pass the live menu wordmark styling across the optional RTC module boundary.
        putClientProperty("wiiuu.cloud.logoFont",MenuView.font(Font.BOLD,100f));
        putClientProperty("wiiuu.cloud.logoAccent",MenuView.ACCENT);
        // A separate mascot instance keeps the ident's timing independent of provisioning.
        AnimatedMascot identMascot=new Mascot();
        putClientProperty("wiiuu.cloud.mascotPainter",(java.util.function.BiConsumer<Graphics2D,float[]>)(g,p)->{
            identMascot.look(p[4],p[5]);
            identMascot.paintWalking(g,p[0],p[1],p[2],p[3]);
        });
        timer=new Timer(50,e->repaint()); // 20 fps only while waiting for the first picture
        timer.setCoalesce(true);
        addPropertyChangeListener("wiiuu.cloud.loadingStatus",e->{
            if(e.getNewValue() instanceof String text){status=text;repaint();}
        });
    }
    @Override public void addNotify(){super.addNotify();timer.start();}
    @Override public void removeNotify(){timer.stop();super.removeNotify();}
    @Override protected void paintComponent(Graphics graphics){
        Graphics2D g=(Graphics2D)graphics.create();
        try{
            int w=getWidth(),h=getHeight();
            if(w<=0||h<=0)return;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            double t=(System.nanoTime()-started)/1e9;
            float scale=Math.min(w/640f,h/360f);
            g.setColor(new Color(0x101923));g.fillRect(0,0,w,h);
            g.translate((w-640*scale)/2,(h-360*scale)/2);g.scale(scale,scale);
            g.setPaint(new GradientPaint(0,0,new Color(0x172b40),640,360,new Color(0x183f50)));
            g.fillRect(0,0,640,360);
            g.setColor(new Color(0x27495c));
            g.fillRoundRect(110,235,420,4,4,4);
            float x=320+(float)Math.sin(t*.8)*85;
            float stride=(float)Math.cos(t*.8)*(float)Math.sin(t*9);
            long cycle=(long)(t/8);
            if(cycle!=lastHop){lastHop=cycle;if(cycle>0)mascot.hop();}
            mascot.look(x+(float)Math.cos(t*.8)*90,150);
            mascot.paintWalking(g,x,235-Math.abs(stride)*2,46,stride);
            g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,20));
            g.setColor(new Color(0xeaf7ff));center(g,"Xbox Cloud Gaming",50);
            g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,16));
            center(g,UiText.ellipsize(status,g.getFontMetrics(),590),286);
            for(int i=0;i<3;i++){
                int alpha=70+(int)(185*(.5+.5*Math.sin(t*4-i*1.3)));
                g.setColor(new Color(88,212,244,alpha));g.fillOval(303+i*14,305,7,7);
            }
        }finally{g.dispose();}
    }
    private static void center(Graphics2D g,String text,int y){
        g.drawString(text,(640-g.getFontMetrics().stringWidth(text))/2f,y);
    }
}
