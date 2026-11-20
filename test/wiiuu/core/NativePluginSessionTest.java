package wiiuu.core;

public final class NativePluginSessionTest {
    public static void main(String[] args) {
        String valid = "{\"id\":\"v1\",\"title\":\"Browse Games\",\"status\":\"Ready\",\"closeButton\":true,\"entries\":[{\"id\":\"e2\",\"title\":\"Test Game\",\"imageURL\":\"https://example.com/cover.png\",\"buttons\":[{\"id\":\"b3\",\"label\":\"Download\",\"busy\":false}]}]}";
        var view = NativePluginSession.parseView(MiniJson.parse(valid));
        if (!view.title().equals("Browse Games") || view.entries().size() != 1 || !view.entries().get(0).buttons().get(0).label().equals("Download"))
            throw new AssertionError("Native view protocol lost content");
        for (String invalid : new String[] { valid.replace("https://example.com/cover.png", "file:///etc/passwd"),
                valid.replace("https://example.com/cover.png", "https://user@example.com/x"), valid.replace("b3", "b\\\"3"),
                valid.replace("\"busy\":false", "\"busy\":\"false\""), valid.replace("\"closeButton\":true", "\"closeButton\":1"),
                valid.replace("\"entries\":[", "\"entries\":{\"invalid\":[") }) {
            try { NativePluginSession.parseView(MiniJson.parse(invalid)); throw new AssertionError("Accepted invalid native model"); }
            catch (IllegalArgumentException expected) { }
        }
        String textModel = """
                {"id":"v1","title":"Search","status":"","closeButton":true,"entries":[{"id":"e1","title":"Game","imageURL":"","buttons":[],"textField":{"id":"t2","placeholder":"Search games","value":"Mario"}}]}
                """;
        var input = NativePluginSession.parseView(MiniJson.parse(textModel)).entries().get(0).textField();
        if (input == null || !input.value().equals("Mario") || !input.id().equals("t2"))
            throw new AssertionError("Native text field protocol lost content");
        try { NativePluginSession.parseView(MiniJson.parse(textModel.replace("\"t2\"", "\"b2\""))); throw new AssertionError("Invalid input ID accepted"); }
        catch (IllegalArgumentException expected) { }
        try { view.entries().clear(); throw new AssertionError("Mutable native model"); } catch (UnsupportedOperationException expected) { }
        System.out.println("Native view protocol validation passed");
    }
}
