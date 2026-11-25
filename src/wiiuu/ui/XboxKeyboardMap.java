package wiiuu.ui;

import wiiuu.core.Config;
import java.util.*;

/** Browser KeyboardEvent.code bindings shared by the settings and Xbox launcher. */
final class XboxKeyboardMap {
    private static final Map<String,String> DEFAULTS;
    private static final List<String> INPUTS;
    static {
        Map<String,String> d = new LinkedHashMap<>();
        String[] targets = {"MoveUp","MoveDown","MoveLeft","MoveRight","A","B","X","Y","LeftShoulder","RightShoulder",
                "LeftTrigger","RightTrigger","View","Menu","LeftThumb","RightThumb","DPadUp","DPadDown","DPadLeft","DPadRight","Nexus"};
        String[] values = {"KeyW","KeyS","KeyA","KeyD","Space","KeyC","KeyR","KeyF","KeyQ","KeyE","Mouse2","Mouse0","Tab","Enter",
                "ShiftLeft","ControlLeft","ArrowUp","ArrowDown","ArrowLeft","ArrowRight","None"};
        for (int i=0;i<targets.length;i++) d.put(targets[i],values[i]);
        DEFAULTS = Collections.unmodifiableMap(d);
        List<String> inputs = new ArrayList<>(List.of("None","Mouse0","Mouse1","Mouse2","Mouse3","Mouse4","Space","Enter","Tab",
                "ShiftLeft","ShiftRight","ControlLeft","ControlRight","AltLeft","AltRight","ArrowUp","ArrowDown","ArrowLeft","ArrowRight",
                "Backspace","Delete","Insert","Home","End","PageUp","PageDown","Comma","Period","Slash","Semicolon","Quote","BracketLeft","BracketRight","Backslash","Minus","Equal","Backquote"));
        for(char ch='A';ch<='Z';ch++) inputs.add("Key"+ch);
        for(int i=0;i<=9;i++){inputs.add("Digit"+i);inputs.add("Numpad"+i);}
        INPUTS=List.copyOf(inputs);
    }
    static Map<String,String> defaults(){return DEFAULTS;}
    static List<String> inputs(){return INPUTS;}
    static String binding(Config config,String target){
        String fallback=DEFAULTS.get(target);
        String value=config.get("xcloud.bind."+target,fallback);
        return INPUTS.contains(value)?value:fallback;
    }
    static String label(String target){
        return target.replaceAll("([a-z])([A-Z])","$1 $2");
    }
    static String serialized(Config config){
        List<String> bindings=new ArrayList<>();
        for(String target:DEFAULTS.keySet())bindings.add(target+":"+binding(config,target));
        return String.join(",",bindings);
    }
    static int sensitivity(Config config){return Math.max(10,Math.min(300,config.getInt("xcloud.mouseSensitivity",100)));}
}
