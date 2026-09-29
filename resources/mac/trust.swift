// WII-UU macOS helper: may WII-UU press keys? Prints "yes" or "no".
// macOS judges a helper by the app that started it, so this answers for WII-UU.app.
// --prompt also asks macOS to show its "allow in Accessibility" dialog when the answer is no.
import ApplicationServices

let prompt = CommandLine.arguments.contains("--prompt")
let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: prompt] as CFDictionary
print(AXIsProcessTrustedWithOptions(options) ? "yes" : "no")
