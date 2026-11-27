package wiiuu.xcloud;

import javax.swing.*;
import java.awt.BorderLayout;

/** EDT-owned handover from the host's branded loading component to the actual decoded video. */
final class LoadingStage extends JPanel {
    private JComponent loading;
    private final JComponent video;
    LoadingStage(JComponent loading,JComponent video){
        super(new BorderLayout());
        this.loading=java.util.Objects.requireNonNull(loading);
        this.video=java.util.Objects.requireNonNull(video);
        add(loading,BorderLayout.CENTER);
    }
    void status(String message){
        if(loading!=null)loading.putClientProperty("wiiuu.cloud.loadingStatus",message);
    }
    void showVideo(){
        if(loading==null)return;
        remove(loading); // removeNotify stops the host animation timer
        loading=null;
        add(video,BorderLayout.CENTER);
        revalidate();repaint();
    }
    static void selfTest(){
        try{
            SwingUtilities.invokeAndWait(()->{
                JPanel mascot=new JPanel(),video=new JPanel();
                LoadingStage stage=new LoadingStage(mascot,video);
                stage.status("Provisioning");
                if(stage.getComponentCount()!=1||stage.getComponent(0)!=mascot
                        ||!"Provisioning".equals(mascot.getClientProperty("wiiuu.cloud.loadingStatus")))
                    throw new AssertionError("Loading stage not visible");
                stage.showVideo();stage.showVideo();
                stage.status("Late status");
                if(stage.getComponentCount()!=1||stage.getComponent(0)!=video
                        ||mascot.getParent()!=null||!"Provisioning".equals(mascot.getClientProperty("wiiuu.cloud.loadingStatus")))
                    throw new AssertionError("First video frame did not replace mascot");
            });
        }catch(Exception e){throw new AssertionError("Loading stage",e);}
    }
}
