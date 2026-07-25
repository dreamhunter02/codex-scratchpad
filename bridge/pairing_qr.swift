#!/usr/bin/env swift
import AppKit
import CoreImage

func value(_ flag: String) -> String? {
    guard let index = CommandLine.arguments.firstIndex(of: flag), CommandLine.arguments.indices.contains(index + 1) else { return nil }
    return CommandLine.arguments[index + 1]
}

guard let endpoint = value("--endpoint"), let output = value("--output") else {
    fputs("Usage: pairing_qr.swift --endpoint http://MAC-LAN-IP:8787 [--token SECRET] --output pairing.png\n", stderr)
    exit(2)
}

var components = URLComponents()
components.scheme = "codex-scratchpad"
components.host = "pair"
components.queryItems = [URLQueryItem(name: "endpoint", value: endpoint)]
if let token = value("--token"), !token.isEmpty { components.queryItems?.append(URLQueryItem(name: "token", value: token)) }
guard let payload = components.url?.absoluteString,
      let filter = CIFilter(name: "CIQRCodeGenerator") else { exit(1) }
filter.setValue(Data(payload.utf8), forKey: "inputMessage")
filter.setValue("M", forKey: "inputCorrectionLevel")
guard let image = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 12, y: 12)) else { exit(1) }
let context = CIContext()
guard let cgImage = context.createCGImage(image, from: image.extent),
      let png = NSBitmapImageRep(cgImage: cgImage).representation(using: .png, properties: [:]) else { exit(1) }
try png.write(to: URL(fileURLWithPath: output))
print("Pairing QR written to \(output)")
print(payload)
