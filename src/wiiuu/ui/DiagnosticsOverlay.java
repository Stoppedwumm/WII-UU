package wiiuu.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import wiiuu.core.Config;
import wiiuu.core.Diagnostics;
import wiiuu.core.BuildInfo;
import wiiuu.input.PadButton;

/** Controller-friendly secret setup-guide diagnostics flow. No log transfer before acceptance. */
final class DiagnosticsOverlay {
    enum Stage { CLOSED, PIN, CONSENT, COMPLETE }
    private final Config config;
    private Stage stage = Stage.CLOSED;
    private final int[] digits = new int[4];
    private int cursor, choice;  // consent choices: 0 = decline; 1 = accept
    private String notice = "";
    private int width = 1280, height = 720;
    private java.awt.geom.Rectangle2D leftButton, rightButton;

    DiagnosticsOverlay(Config config) { this.config = config; }
    boolean active() { return stage != Stage.CLOSED; }
    Stage stage() { return stage; }
    boolean wantsKeys() { return stage == Stage.PIN; }

    void open() {
        stage = Stage.PIN;
        cursor = 0; choice = 0; notice = "";
        java.util.Arrays.fill(digits, 0);
    }
    void navigate(int dx, int dy) {
        if (stage == Stage.PIN) {
            if (dx != 0) cursor = Math.max(0, Math.min(3, cursor + Integer.signum(dx)));
            if (dy != 0) digits[cursor] = Math.floorMod(digits[cursor] - Integer.signum(dy), 10);
        } else if (stage == Stage.CONSENT && dx != 0) {
            choice = Math.max(0, Math.min(1, choice + Integer.signum(dx)));
        }
    }
    void activate() {
        switch(stage) {
            case PIN -> {
                if (cursor < 3) cursor++;
                else {stage = Stage.CONSENT; choice = 0;}
            }
            case CONSENT -> {
                if (choice == 0) {stage = Stage.CLOSED; break;}
                try {
                    StringBuilder pin = new StringBuilder();
                    for (int d : digits) pin.append(d);
                    Diagnostics.enable(config, pin.toString());
                    Diagnostics.runSoon(config, BuildInfo.version());
                    notice = "Diagnostic sharing enabled. PIN " + pin + ".";
                    stage = Stage.COMPLETE;
                } catch (IOException e) {notice = "Could not save consent: " + e.getMessage();stage = Stage.COMPLETE;}
            }
            case COMPLETE -> stage = Stage.CLOSED;
            default -> {}
        }
    }
    void back() {
        switch(stage) {
            case PIN -> stage = Stage.CLOSED;
            case CONSENT -> stage = Stage.PIN;
            case COMPLETE -> stage = Stage.CLOSED;
            default -> {}
        }
    }
    boolean pressed(PadButton b, boolean down) {
        if (!active()) return false;
        if (!down) return true;
        if (b == PadButton.B || b == PadButton.HOME) back();
        else if (b == PadButton.A || b == PadButton.PLUS) activate();
        return true;
    }
    void keyTyped(char c) {
        if (stage == Stage.PIN && c >= '0' && c <= '9') {
            digits[cursor] = c - '0';
            if (cursor < 3) cursor++;
        }
    }
    void keyPressed(KeyEvent e) {
        if (!active()) return;
        switch(e.getKeyCode()) {
            case KeyEvent.VK_LEFT -> navigate(-1,0);
            case KeyEvent.VK_RIGHT -> navigate(1,0);
            case KeyEvent.VK_UP -> navigate(0,-1);
            case KeyEvent.VK_DOWN -> navigate(0,1);
            case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> activate();
            case KeyEvent.VK_BACK_SPACE -> {
                if (stage == Stage.PIN) { digits[cursor]=0; cursor=Math.max(0,cursor-1); }
                else back();
            }
            case KeyEvent.VK_ESCAPE -> back();
            default -> {}
        }
    }
    void click(double x, double y) {
        if (!active()) return;
        if (leftButton != null && leftButton.contains(x,y)) {
            if(stage==Stage.PIN) back();
            else if(stage==Stage.CONSENT) {choice=0;activate();}
            else back();
            return;
        }
        if (rightButton != null && rightButton.contains(x,y)) {
            if(stage==Stage.CONSENT)choice=1;
            activate();
            return;
        }
        if(stage==Stage.PIN) {
            double x0=width*.30, cell=width*.10;
            for(int i=0;i<4;i++)if(x>=x0+i*cell && x<=x0+(i+1)*cell
                    && y>=height*.40 && y<=height*.60) {cursor=i;return;}
        }
    }
    void paint(Graphics2D g,int w,int h) {
        if (!active()) return;
        width=w;height=h;
        Graphics2D c=(Graphics2D)g.create();
        c.setComposite(AlphaComposite.SrcOver);
        c.setColor(new Color(0,0,0,185));c.fillRect(0,0,w,h);
        int pw=(int)(w*.80), ph=(int)(h*.75), x=(w-pw)/2, y=(h-ph)/2;
        c.setColor(MenuView.dark?new Color(32,45,58):new Color(245,248,252));
        c.fill(new RoundRectangle2D.Double(x,y,pw,ph,25,25));
        c.setColor(MenuView.TEXT);
        int font=Math.max(16,(int)(w*.019));
        c.setFont(MenuView.font(Font.BOLD,font+8));
        String title=switch(stage) {
            case PIN -> "Diagnostic PIN";
            case CONSENT -> "Optional diagnostic sharing";
            case COMPLETE -> "Diagnostics";
            default -> "";
        };
        center(c,title,w/2,y+font*3);
        c.setFont(MenuView.font(Font.PLAIN,font));
        int lineY=y+font*5;
        String message=switch(stage) {
            case PIN -> "Enter a four-digit label for your diagnostic reports. This PIN is NOT a password. "
                    + "Use up/down to set each digit, left/right to move; A or Enter continues.";
            case CONSENT -> Diagnostics.DISCLAIMER + " Do you explicitly agree to send these reports?";
            case COMPLETE -> notice;
            default -> "";
        };
        for(String line:wrap(c,message,pw-75)){
            c.drawString(line,x+38,lineY);lineY+=font+7;
        }
        if(stage==Stage.PIN) {
            int gap=Math.min(100,pw/6), start=w/2-gap*2;
            c.setFont(MenuView.font(Font.BOLD,font+30));
            for(int i=0;i<4;i++){
                c.setColor(i==cursor?MenuView.ACCENT:MenuView.TEXT);
                center(c,String.valueOf(digits[i]),start+i*gap+gap/2,(int)(y+ph*.68));
            }
        }
        int buttonWidth=Math.min(210,pw/3), buttonHeight=Math.max(42,h/14);
        int by=y+ph-buttonHeight-30;
        leftButton=new java.awt.geom.Rectangle2D.Double(w/2-buttonWidth-18,by,buttonWidth,buttonHeight);
        rightButton=new java.awt.geom.Rectangle2D.Double(w/2+18,by,buttonWidth,buttonHeight);
        String left=stage==Stage.PIN?"Cancel":stage==Stage.CONSENT?"Decline":"Close";
        String right=stage==Stage.PIN?"Next":stage==Stage.CONSENT?"I Agree":"Close";
        drawButton(c,leftButton,left,stage==Stage.CONSENT&&choice==0);
        drawButton(c,rightButton,right,stage!=Stage.CONSENT||choice==1);
        c.dispose();
    }
    private static void drawButton(Graphics2D g,java.awt.geom.Rectangle2D b,String label,boolean chosen){
        g.setColor(chosen?MenuView.ACCENT:new Color(105,118,130));
        g.fill(new RoundRectangle2D.Double(b.getX(),b.getY(),b.getWidth(),b.getHeight(),18,18));
        g.setColor(Color.WHITE);
        center(g,label,(int)b.getCenterX(),(int)(b.getCenterY()+g.getFontMetrics().getAscent()*.35));
    }
    private static void center(Graphics2D g,String t,int x,int y){
        FontMetrics f=g.getFontMetrics();g.drawString(t,x-f.stringWidth(t)/2,y);
    }
    private static List<String> wrap(Graphics2D g,String s,int max){
        List<String> result=new ArrayList<>();StringBuilder line=new StringBuilder();
        for(String word:s.split("\\s+")){
            String candidate=line.isEmpty()?word:line+" "+word;
            if(g.getFontMetrics().stringWidth(candidate)>max && !line.isEmpty()){
                result.add(line.toString());line.setLength(0);
            }
            if(!line.isEmpty())line.append(' ');
            line.append(word);
        }
        if(!line.isEmpty())result.add(line.toString());
        return result;
    }
}
