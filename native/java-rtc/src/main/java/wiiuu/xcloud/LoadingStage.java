package wiiuu.xcloud;

import javax.swing.*;
import java.awt.*;

/** EDT-owned loading -> logo -> live video handover. No queued video is replayed. */
final class LoadingStage extends JPanel {
    private JComponent loading;
    private CloudLogoIntro intro;
    private final JComponent video;
    private volatile boolean videoVisible;
    LoadingStage(JComponent loading,JComponent video){
        super(new BorderLayout());
        this.loading=java.util.Objects.requireNonNull(loading);
        this.video=java.util.Objects.requireNonNull(video);
        add(loading,BorderLayout.CENTER);
    }
    void status(String message){
        if(loading!=null)loading.putClientProperty("wiiuu.cloud.loadingStatus",message);
    }
    boolean videoVisible(){return videoVisible;}
    void beginIntro(){
        if(loading==null)return;
        Object font=loading.getClientProperty("wiiuu.cloud.logoFont");
        Object accent=loading.getClientProperty("wiiuu.cloud.logoAccent");
        remove(loading); // stops the mascot timer
        loading=null;
        intro=new CloudLogoIntro(font instanceof Font f?f:new Font(Font.SANS_SERIF,Font.BOLD,100),
                accent instanceof Color c?c:new Color(0x58d4f4),this::showVideo);
        add(intro,BorderLayout.CENTER);
        revalidate();repaint();
    }
    private void showVideo(){
        if(intro==null)return;
        remove(intro); // stops the logo timer
        intro=null;
        add(video,BorderLayout.CENTER);
        videoVisible=true;
        revalidate();repaint();
    }
    static void selfTest(){
        try{
            SwingUtilities.invokeAndWait(()->{
                JPanel mascot=new JPanel(),video=new JPanel();
                LoadingStage stage=new LoadingStage(mascot,video);
                stage.status("Provisioning");
                if(stage.getComponentCount()!=1||stage.getComponent(0)!=mascot
                        ||!"Provisioning".equals(mascot.getClientProperty("wiiuu.cloud.loadingStatus"))||stage.videoVisible())
                    throw new AssertionError("Loading stage not visible");
                stage.beginIntro();stage.beginIntro();
                if(stage.getComponentCount()!=1||!(stage.getComponent(0) instanceof CloudLogoIntro)
                        ||mascot.getParent()!=null||stage.videoVisible())
                    throw new AssertionError("First video frame did not start logo");
                CloudLogoIntro logo=stage.intro;
                stage.status("Late status");
                stage.showVideo();stage.showVideo();stage.beginIntro();
                if(stage.getComponentCount()!=1||stage.getComponent(0)!=video||logo.getParent()!=null
                        ||!stage.videoVisible()||!"Provisioning".equals(mascot.getClientProperty("wiiuu.cloud.loadingStatus")))
                    throw new AssertionError("Logo did not hand over to live video");
            });
        }catch(Exception e){throw new AssertionError("Loading stage",e);}
    }
}
