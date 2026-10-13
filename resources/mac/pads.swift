// WII-UU's controller helper for macOS: reads USB / Bluetooth controllers through Apple's
// GameController framework (Xbox, PlayStation, Switch Pro, MFi and most other pads) and prints
// their input, one line each, the way WII-UU's Linux helper (vpad.py) does:
//
//   pad+ <id> <name>          a controller came
//   pad- <id>                 ... went
//   in <id> b <BUTTON> <0|1>  a button, named as the GamePad's button at the same place
//                             (A right, B bottom, X top, Y left, L R ZL ZR PLUS MINUS HOME L3 R3,
//                             UP DOWN LEFT RIGHT)
//   in <id> s <0|1> <x> <y>   a stick (0 left, 1 right), -1..1, +y down
//
// It keeps reading while WII-UU's window isn't in front (shouldMonitorBackgroundEvents).
// Built by WII-UU on first use (xcrun swiftc), like its other helpers.

import Foundation
import GameController

setvbuf(stdout, nil, _IOLBF, 0)

final class Pads {
    // the controller is kept with its id: a replugged one can't then get a gone one's identity
    private var ids: [ObjectIdentifier: (GCController, String)] = [:]
    private var next = 0
    private var held: [String: Bool] = [:]
    private var sticks: [String: (Float, Float)] = [:]

    func say(_ line: String) {
        print(line)
    }

    func button(_ id: String, _ name: String, _ down: Bool) {
        let key = id + "|" + name
        if held[key] == down { return }
        held[key] = down
        say("in \(id) b \(name) \(down ? 1 : 0)")
    }

    func stick(_ id: String, _ n: Int, _ x: Float, _ y: Float) {
        let dead: Float = 0.12
        func shape(_ v: Float) -> Float {
            if abs(v) < dead { return 0 }
            let s = (abs(v) - dead) / (1 - dead)
            return v < 0 ? -min(1, s) : min(1, s)
        }
        let xy = ((shape(x) * 1000).rounded() / 1000, (shape(-y) * 1000).rounded() / 1000)   // GameController: +y is up
        let key = id + "|\(n)"
        if let was = sticks[key], was == xy { return }
        sticks[key] = xy
        say(String(format: "in %@ s %d %.3f %.3f", id, n, xy.0, xy.1))
    }

    func attach(_ c: GCController) {
        let oid = ObjectIdentifier(c)
        if ids[oid] != nil { return }
        guard let g = c.extendedGamepad else { return }      // only full gamepads
        let id = "mac\(next)"
        next += 1
        ids[oid] = (c, id)
        let name = (c.vendorName ?? c.productCategory).replacingOccurrences(of: "\n", with: " ")
        say("pad+ \(id) \(name.isEmpty ? "Controller" : name)")

        // by place: Apple's buttonA is the bottom one (the GamePad's B), buttonB the right one (A)...
        func on(_ b: GCControllerButtonInput?, _ name: String) {
            b?.pressedChangedHandler = { [weak self] _, _, pressed in self?.button(id, name, pressed) }
        }
        on(g.buttonA, "B")
        on(g.buttonB, "A")
        on(g.buttonX, "Y")
        on(g.buttonY, "X")
        on(g.leftShoulder, "L")
        on(g.rightShoulder, "R")
        on(g.leftTrigger, "ZL")
        on(g.rightTrigger, "ZR")
        on(g.buttonMenu, "PLUS")
        on(g.buttonOptions, "MINUS")
        on(g.buttonHome, "HOME")
        on(g.leftThumbstickButton, "L3")
        on(g.rightThumbstickButton, "R3")
        on(g.dpad.up, "UP")
        on(g.dpad.down, "DOWN")
        on(g.dpad.left, "LEFT")
        on(g.dpad.right, "RIGHT")
        g.leftThumbstick.valueChangedHandler = { [weak self] _, x, y in self?.stick(id, 0, x, y) }
        g.rightThumbstick.valueChangedHandler = { [weak self] _, x, y in self?.stick(id, 1, x, y) }
    }

    func detach(_ c: GCController) {
        guard let id = ids.removeValue(forKey: ObjectIdentifier(c))?.1 else { return }
        held = held.filter { !$0.key.hasPrefix(id + "|") }
        sticks = sticks.filter { !$0.key.hasPrefix(id + "|") }
        say("pad- \(id)")
    }
}

let pads = Pads()
if #available(macOS 11.3, *) {
    GCController.shouldMonitorBackgroundEvents = true
}
let center = NotificationCenter.default
center.addObserver(forName: .GCControllerDidConnect, object: nil, queue: .main) { n in
    if let c = n.object as? GCController { pads.attach(c) }
}
center.addObserver(forName: .GCControllerDidDisconnect, object: nil, queue: .main) { n in
    if let c = n.object as? GCController { pads.detach(c) }
}
for c in GCController.controllers() { pads.attach(c) }
GCController.startWirelessControllerDiscovery {}

// WII-UU closing its end of the pipe ends the helper
DispatchQueue.global().async {
    while readLine() != nil {}
    exit(0)
}
RunLoop.main.run()
