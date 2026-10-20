import Foundation

enum TVConnectionActivity {
    case idle, checking, discovering, pairing
}

/// Presentation follows actual network/pairing work, never unrelated import or conversion activity.
enum TVConnectionState: Equatable {
    case unpaired, disconnected, connected, checking, discovering, awaitingCode, pairing

    static func resolve(activity: TVConnectionActivity, isPaired: Bool, hasCertificateChallenge: Bool, hasAuthenticatedStatus: Bool) -> TVConnectionState {
        switch activity {
        // Periodic authenticated probes do not invalidate an already verified
        // connection. A failure clears that status and then resolves to disconnected.
        case .checking: return isPaired && hasAuthenticatedStatus ? .connected : .checking
        case .discovering: return .discovering
        case .pairing: return .pairing
        case .idle: break
        }
        if hasCertificateChallenge { return .awaitingCode }
        guard isPaired else { return .unpaired }
        return hasAuthenticatedStatus ? .connected : .disconnected
    }
}

/// Authenticated status checks remain inexpensive while a TV is awake and retry
/// promptly after it wakes. The capped sequence avoids both duplicate probes and
/// a tight loop against a sleeping receiver.
enum TVAutomaticReconnectPolicy {
    static let steadyStateInterval: TimeInterval = 20
    static let statusRequestTimeout: TimeInterval = 8
    private static let retryIntervals: [TimeInterval] = [2, 4, 8, 10]

    static func refreshDelay(afterFailures failures: Int) -> TimeInterval {
        guard failures > 0 else { return steadyStateInterval }
        return retryIntervals[min(failures - 1, retryIntervals.count - 1)]
    }

    static func shouldAttempt(now: Date, nextAllowedAt: Date) -> Bool {
        now >= nextAllowedAt
    }

    static func selfTestsPass() -> Bool {
        let now = Date(timeIntervalSinceReferenceDate: 1_000)
        return refreshDelay(afterFailures: 0) == 20
            && refreshDelay(afterFailures: 1) == 2
            && refreshDelay(afterFailures: 2) == 4
            && refreshDelay(afterFailures: 3) == 8
            && refreshDelay(afterFailures: 4) == 10
            && refreshDelay(afterFailures: 5) == 10
            && refreshDelay(afterFailures: 99) == 10
            && shouldAttempt(now: now, nextAllowedAt: now)
            && !shouldAttempt(now: now, nextAllowedAt: now.addingTimeInterval(0.001))
    }
}

/// Network monitoring is edge-triggered: the initial path callback is only a
/// baseline, while recovery or a route-interface change requests a fresh status.
enum TVNetworkRecoveryPolicy {
    static func shouldRequestReconnect(
        hasObservedPath: Bool,
        wasSatisfied: Bool,
        previousInterfaces: Set<String>,
        isSatisfied: Bool,
        interfaces: Set<String>
    ) -> Bool {
        guard hasObservedPath else { return false }
        return (!wasSatisfied && isSatisfied)
            || (isSatisfied && previousInterfaces != interfaces)
    }

    static func selfTestsPass() -> Bool {
        !shouldRequestReconnect(hasObservedPath: false, wasSatisfied: false, previousInterfaces: [], isSatisfied: true, interfaces: ["wifi"])
            && !shouldRequestReconnect(hasObservedPath: true, wasSatisfied: true, previousInterfaces: ["wifi"], isSatisfied: false, interfaces: [])
            && shouldRequestReconnect(hasObservedPath: true, wasSatisfied: false, previousInterfaces: [], isSatisfied: true, interfaces: ["wifi"])
            && shouldRequestReconnect(hasObservedPath: true, wasSatisfied: true, previousInterfaces: ["wifi"], isSatisfied: true, interfaces: ["wiredEthernet"])
    }
}

enum TVConnectionActionPolicy {
    static func isConnected(state: TVConnectionState, isPaired: Bool, hasAuthenticatedStatus: Bool) -> Bool {
        state == .connected && isPaired && hasAuthenticatedStatus
    }

    static func canSync(state: TVConnectionState, isPaired: Bool, hasAuthenticatedStatus: Bool, isBusy: Bool, hasActiveMedia: Bool) -> Bool {
        isConnected(state: state, isPaired: isPaired, hasAuthenticatedStatus: hasAuthenticatedStatus)
            && !isBusy && hasActiveMedia
    }

    static func canAutoRefresh(isPaired: Bool, isBusy: Bool, hasPairingChallenge: Bool, isLocallyDisconnected: Bool) -> Bool {
        isPaired && !isBusy && !hasPairingChallenge && !isLocallyDisconnected
    }
}
