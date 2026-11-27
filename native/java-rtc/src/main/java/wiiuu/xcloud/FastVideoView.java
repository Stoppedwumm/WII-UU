package wiiuu.xcloud;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import javax.swing.JComponent;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.*;
import java.util.concurrent.atomic.AtomicLong;

/** Reuse images and native conversion memory; avoid an affine transform for ordinary cloud video. */
final class FastVideoView extends JComponent {
    private final Object lock=new Object();
    private final boolean fastScale;
    private final AtomicLong paints=new AtomicLong();
    private BufferedImage front,back;
    private ByteBuffer rgb;
    private int rotation;
    FastVideoView(boolean fastScale){
        this.fastScale=fastScale;
        setOpaque(true);setBackground(Color.BLACK);setPreferredSize(new Dimension(640,360));
    }
    // Exactly one conversion worker owns back and rgb; the EDT only reads front.
    void accept(VideoFrame frame){
        int w=frame.buffer.getWidth(),h=frame.buffer.getHeight();
        long bytes=4L*w*h;
        if(w<=0||h<=0||bytes>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid video size");
        if(back==null||back.getWidth()!=w||back.getHeight()!=h)back=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        if(rgb==null||rgb.capacity()<bytes)rgb=ByteBuffer.allocateDirect((int)bytes).order(ByteOrder.LITTLE_ENDIAN);
        rgb.clear();rgb.limit((int)bytes);
        try{VideoBufferConverter.convertFromI420(frame.buffer,rgb,FourCC.ARGB);}
        catch(Exception e){throw new IllegalStateException("Video conversion failed",e);}
        rgb.asIntBuffer().get(((DataBufferInt)back.getRaster().getDataBuffer()).getData());
        synchronized(lock){
            BufferedImage old=front;front=back;back=old;rotation=frame.rotation;
        }
        repaint();
    }
    long painted(){return paints.get();}
    static Rectangle fit(int w,int h,int sw,int sh){
        double scale=Math.min((double)w/sw,(double)h/sh);
        int dw=(int)Math.round(sw*scale),dh=(int)Math.round(sh*scale);
        return new Rectangle((w-dw)/2,(h-dh)/2,dw,dh);
    }
    @Override protected void paintComponent(Graphics graphics){
        synchronized(lock){
            if(front==null){graphics.setColor(Color.BLACK);graphics.fillRect(0,0,getWidth(),getHeight());return;}
            int w=front.getWidth(),h=front.getHeight();
            boolean sideways=rotation==90||rotation==270;
            Rectangle r=fit(getWidth(),getHeight(),sideways?h:w,sideways?w:h);
            graphics.setColor(Color.BLACK);
            // Clear letterbox bars, not the area about to be overwritten by opaque video.
            graphics.fillRect(0,0,getWidth(),r.y);
            graphics.fillRect(0,r.y+r.height,getWidth(),getHeight()-r.y-r.height);
            graphics.fillRect(0,r.y,r.x,r.height);
            graphics.fillRect(r.x+r.width,r.y,getWidth()-r.x-r.width,r.height);
            if(rotation==0&&r.width==w&&r.height==h){
                graphics.drawImage(front,r.x,r.y,null);
            }else{
                Graphics2D g=(Graphics2D)graphics.create();
                try{
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,fastScale?
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR:RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    if(rotation==0)g.drawImage(front,r.x,r.y,r.width,r.height,null);
                    else{
                        g.translate(r.x+r.width/2.0,r.y+r.height/2.0);g.rotate(Math.toRadians(rotation));
                        int dw=sideways?r.height:r.width,dh=sideways?r.width:r.height;
                        g.drawImage(front,-dw/2,-dh/2,dw,dh,null);
                    }
                }finally{g.dispose();}
            }
            paints.incrementAndGet();
        }
    }
    static void selfTest(){
        if(!fit(1280,768,1280,720).equals(new Rectangle(0,24,1280,720)))throw new AssertionError("Video aspect ratio");
        FastVideoView v=new FastVideoView(true);v.setSize(4,4);
        v.front=new BufferedImage(4,2,BufferedImage.TYPE_INT_RGB);
        v.front.setRGB(0,0,0xff336699);
        BufferedImage dst=new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB);
        Graphics g=dst.getGraphics();
        try{v.paintComponent(g);}finally{g.dispose();}
        if(dst.getRGB(0,1)!=0xff336699||dst.getRGB(0,0)!=0xff000000||v.painted()!=1)throw new AssertionError("Video paint");
    }
}
