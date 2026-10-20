import Foundation

/// Resolves one receiver-owned section at a time. A persisted local edit wins only until the
/// receiver accepts that exact value; otherwise the latest authenticated TV status is adopted.
enum SettingsSyncPolicy {
    static func action<Value: Equatable>(pendingLocalEdit: Bool, local: Value, remote: Value?) -> Action {
        guard pendingLocalEdit else { return .adoptRemote }
        return remote == local ? .acknowledgeLocal : .deliverLocal
    }

    enum Action: Equatable {
        case adoptRemote
        case acknowledgeLocal
        case deliverLocal
    }

    static func selfTestsPass() -> Bool {
        action(pendingLocalEdit: false, local: "old Mac value", remote: "TV value") == .adoptRemote
            && action(pendingLocalEdit: true, local: "Mac edit", remote: "TV value") == .deliverLocal
            && action(pendingLocalEdit: true, local: "Mac edit", remote: "Mac edit") == .acknowledgeLocal
    }
}
