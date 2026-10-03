import UIKit
import UniformTypeIdentifiers

final class ShareViewController: UIViewController {
    private let label = UILabel()
    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor = .systemBackground
        label.text = "正在保存到影子阅读器…"; label.numberOfLines = 0; label.textAlignment = .center; label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label); NSLayoutConstraint.activate([label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24), label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24), label.centerYAnchor.constraint(equalTo: view.centerYAnchor)])
        Task { await receive() }
    }
    @MainActor private func receive() async {
        do {
            let items = extensionContext?.inputItems as? [NSExtensionItem] ?? []
            var link: String?, text: String?
            for provider in items.flatMap({ $0.attachments ?? [] }) {
                if link == nil && provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                    let value = try await load(provider, type: UTType.url.identifier)
                    link = (value as? URL)?.absoluteString ?? (value as? String)
                } else if text == nil && provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                    let value = try await load(provider, type: UTType.plainText.identifier)
                    text = value as? String ?? (value as? Data).flatMap { String(data: $0, encoding: .utf8) }
                }
            }
            guard link != nil || text != nil else { throw NSError(domain: "ShadowReader.Share", code: 3, userInfo: [NSLocalizedDescriptionKey: "请选择网页链接或英文正文分享。"]) }
            try ShareInbox.save(SharedImport(id: UUID().uuidString, title: items.first?.attributedTitle?.string ?? "", text: text, url: link))
            label.text = "已保存。打开影子阅读器后预览并导入。"
            extensionContext?.completeRequest(returningItems: nil)
        } catch {
            label.text = error.localizedDescription
            let close = UIButton(type: .system); close.setTitle("关闭", for: .normal); close.addTarget(self, action: #selector(cancel), for: .touchUpInside)
            close.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(close)
            NSLayoutConstraint.activate([close.topAnchor.constraint(equalTo: label.bottomAnchor, constant: 24), close.centerXAnchor.constraint(equalTo: view.centerXAnchor)])
        }
    }
    private func load(_ provider: NSItemProvider, type: String) async throws -> NSSecureCoding {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadItem(forTypeIdentifier: type, options: nil) { value, error in
                if let error { continuation.resume(throwing: error) }
                else if let value { continuation.resume(returning: value) }
                else { continuation.resume(throwing: NSError(domain: "ShadowReader.Share", code: 4, userInfo: [NSLocalizedDescriptionKey: "分享内容为空。"])) }
            }
        }
    }
    @objc private func cancel() { extensionContext?.cancelRequest(withError: NSError(domain: "ShadowReader.Share", code: NSUserCancelledError)) }
}
