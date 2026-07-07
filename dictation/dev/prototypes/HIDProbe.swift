// HIDProbe.swift — read-only IOHIDManager probe (throwaway diagnostic, not shipped).
//
// Purpose: prove how Wispr grabs Fn without an Input Monitoring entry. It watches raw HID input
// values and prints which usage-page/usage the Fn/Globe key emits, and — critically — whether
// simply opening the HID devices triggers a macOS "Input Monitoring" prompt. NO device seizing,
// NO event posting: pure observation, safe to run.
//
// Run:   swift dictation/dev/prototypes/HIDProbe.swift
// Then:  press Fn a few times, then a normal letter key, then Ctrl-C.
//
// What to report back:
//   1. Did macOS pop an "Input Monitoring" permission prompt when it started?
//      - If it did, you can DENY it and keep going — then tell me whether Fn STILL prints below.
//   2. The exact line(s) printed ONLY when you press Fn (the page=… usage=… value=…).
//   3. Whether a normal letter key also prints (tells us if the keyboard page is gated separately).

import Foundation
import IOKit.hid

let manager = IOHIDManagerCreate(kCFAllocatorDefault, IOOptionBits(kIOHIDOptionsTypeNone))

// Match ALL devices so we don't miss whichever device/page exposes Fn. We filter noise (mouse
// axes) in the callback instead, and only surface key-like usages.
IOHIDManagerSetDeviceMatching(manager, nil)

func productName(for device: IOHIDDevice?) -> String {
    guard let device else { return "?" }
    let p = IOHIDDeviceGetProperty(device, kIOHIDProductKey as CFString) as? String
    return p ?? "?"
}

let callback: IOHIDValueCallback = { _, _, sender, value in
    let element = IOHIDValueGetElement(value)
    let page = IOHIDElementGetUsagePage(element)
    let usage = IOHIDElementGetUsage(element)
    let intVal = IOHIDValueGetIntegerValue(value)

    // Only key-like usages: Keyboard/Keypad (0x07), Consumer (0x0C), and Apple vendor top-case
    // pages (>= 0xFF00, where Fn/Globe usually lives). Skip GenericDesktop mouse/axis noise.
    guard page == 0x07 || page == 0x0C || page >= 0xFF00 else { return }
    // Skip idle 0->0 chatter; show real transitions.
    guard intVal != 0 || page >= 0xFF00 else { return }

    let dev = sender.map { Unmanaged<IOHIDDevice>.fromOpaque($0).takeUnretainedValue() }
    let line = String(
        format: "page=0x%04X usage=0x%04X value=%ld  [%@]\n",
        page, usage, intVal, productName(for: dev)
    )
    FileHandle.standardOutput.write(line.data(using: .utf8)!)
}

IOHIDManagerRegisterInputValueCallback(manager, callback, nil)
IOHIDManagerScheduleWithRunLoop(manager, CFRunLoopGetCurrent(), CFRunLoopMode.defaultMode.rawValue)

let rc = IOHIDManagerOpen(manager, IOOptionBits(kIOHIDOptionsTypeNone))
if rc != kIOReturnSuccess {
    FileHandle.standardError.write(
        String(format: "IOHIDManagerOpen FAILED: 0x%08X (likely permission-gated)\n", rc).data(using: .utf8)!)
    exit(1)
}

print("HID probe running (read-only). Press Fn a few times, then a letter key. Ctrl-C to stop.")
print("Looking for: the page/usage that changes ONLY on Fn.\n")
CFRunLoopRun()
