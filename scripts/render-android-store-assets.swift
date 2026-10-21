import AppKit

// Compose store identity assets from the existing, unchanged product mark and system typography.
// Run with: swift scripts/render-android-store-assets.swift SOURCE OUTPUT
let source = URL(fileURLWithPath: CommandLine.arguments[1])
let output = URL(fileURLWithPath: CommandLine.arguments[2])
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
let mark = NSImage(contentsOf: source.appendingPathComponent("shared/app-icon-master.png"))!
func render(_ name: String, width: Int, height: Int) throws {
    let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height,
        bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
        colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
    let w = CGFloat(width), h = CGFloat(height)
    NSColor.black.setFill()
    NSBezierPath(rect: NSRect(x: 0, y: 0, width: w, height: h)).fill()
    let logo = h * 0.68
    mark.draw(in: NSRect(x: w * 0.055, y: (h-logo)/2, width: logo, height: logo))
    let x = w * 0.055 + logo + w * 0.025
    let title = NSAttributedString(string: "Glass TV", attributes: [
        .font: NSFont.systemFont(ofSize: h * 0.185, weight: .semibold), .foregroundColor: NSColor.white])
    title.draw(at: NSPoint(x: x, y: h * (width > 320 ? 0.50 : 0.40)))
    if width > 320 {
        NSAttributedString(string: "Custom Android TV launcher", attributes: [
            .font: NSFont.systemFont(ofSize: h * 0.046, weight: .regular),
            .foregroundColor: NSColor(white: 0.72, alpha: 1)])
            .draw(at: NSPoint(x: x, y: h * 0.405))
    }
    NSGraphicsContext.restoreGraphicsState()
    try bitmap.representation(using: .png, properties: [:])!.write(to: output.appendingPathComponent(name))
}
try render("tv-banner-320x180.png", width: 320, height: 180)
try render("feature-graphic-1024x500.png", width: 1024, height: 500)
