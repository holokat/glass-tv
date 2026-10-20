import AppKit
import SwiftUI

/// A native AppKit media grid. Selection is intentionally separate from rotation inclusion:
/// selection drives batch actions, while the contextual actions preserve the local playlist.
struct MediaCollectionView: NSViewRepresentable {
    let items: [LocalPhoto]
    @Binding var selection: Set<UUID>
    let onToggleRotation: (LocalPhoto) -> Void
    let onMove: (LocalPhoto, Int) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    func makeNSView(context: Context) -> NSScrollView {
        let layout = AdaptiveMediaLayout()
        let collection = NativeMediaCollectionView(frame: .zero)
        collection.collectionViewLayout = layout
        collection.isSelectable = true
        collection.allowsMultipleSelection = true
        collection.backgroundColors = [NSColor.clear]
        collection.register(MediaCollectionItem.self, forItemWithIdentifier: MediaCollectionItem.reuseIdentifier)
        collection.delegate = context.coordinator
        collection.dataSource = context.coordinator
        collection.onSelectionChanged = { [weak coordinator = context.coordinator] in coordinator?.publishSelection() }

        let scrollView = MediaGridScrollView()
        scrollView.drawsBackground = false
        scrollView.hasVerticalScroller = true
        scrollView.autohidesScrollers = true
        scrollView.borderType = .noBorder
        scrollView.hasHorizontalScroller = false
        scrollView.documentView = collection
        scrollView.collectionView = collection
        context.coordinator.collectionView = collection
        context.coordinator.apply(parent: self)
        collection.reloadData()
        context.coordinator.applySelectionToCollection()
        return scrollView
    }

    func updateNSView(_ scrollView: NSScrollView, context: Context) {
        guard let collection = context.coordinator.collectionView else { return }
        if context.coordinator.apply(parent: self) { collection.reloadData() }
        context.coordinator.applySelectionToCollection()
    }

    final class Coordinator: NSObject, NSCollectionViewDataSource, NSCollectionViewDelegate {
        private var parent: MediaCollectionView
        fileprivate weak var collectionView: NativeMediaCollectionView?
        private var applyingSelection = false
        private var lastItems: [LocalPhoto] = []

        init(parent: MediaCollectionView) { self.parent = parent }

        @discardableResult
        func apply(parent: MediaCollectionView) -> Bool {
            let changed = lastItems != parent.items
            self.parent = parent
            if changed {
                lastItems = parent.items
                collectionView?.selectionAnchor = nil
            }
            return changed
        }

        func numberOfSections(in collectionView: NSCollectionView) -> Int { 1 }

        func collectionView(_ collectionView: NSCollectionView, numberOfItemsInSection section: Int) -> Int {
            parent.items.count
        }

        func collectionView(_ collectionView: NSCollectionView, itemForRepresentedObjectAt indexPath: IndexPath) -> NSCollectionViewItem {
            guard let item = collectionView.makeItem(withIdentifier: MediaCollectionItem.reuseIdentifier, for: indexPath) as? MediaCollectionItem,
                  parent.items.indices.contains(indexPath.item) else { return NSCollectionViewItem() }
            let media = parent.items[indexPath.item]
            item.configure(
                media: media,
                selected: parent.selection.contains(media.id),
                onSelectionToggle: { [weak self] in self?.toggleSelection(at: indexPath) },
                onToggleRotation: { [weak self] in self?.parent.onToggleRotation(media) },
                onMove: { [weak self] offset in self?.parent.onMove(media, offset) }
            )
            return item
        }

        func collectionView(_ collectionView: NSCollectionView, didSelectItemsAt indexPaths: Set<IndexPath>) {
            publishSelection()
        }

        func collectionView(_ collectionView: NSCollectionView, didDeselectItemsAt indexPaths: Set<IndexPath>) {
            publishSelection()
        }

        func toggleSelection(at indexPath: IndexPath) {
            guard let collectionView else { return }
            collectionView.selectionAnchor = indexPath
            var selected = collectionView.selectionIndexPaths
            if selected.contains(indexPath) { selected.remove(indexPath) }
            else { selected.insert(indexPath) }
            collectionView.selectionIndexPaths = selected
            publishSelection()
        }

        func publishSelection() {
            guard !applyingSelection, let collectionView else { return }
            parent.selection = Set(collectionView.selectionIndexPaths.compactMap { indexPath in
                parent.items.indices.contains(indexPath.item) ? parent.items[indexPath.item].id : nil
            })
            refreshVisibleSelection()
        }

        func applySelectionToCollection() {
            guard let collectionView else { return }
            let desired = Set(parent.items.enumerated().compactMap { index, media in
                parent.selection.contains(media.id) ? IndexPath(item: index, section: 0) : nil
            })
            guard collectionView.selectionIndexPaths != desired else {
                refreshVisibleSelection()
                return
            }
            applyingSelection = true
            collectionView.selectionIndexPaths = desired
            applyingSelection = false
            refreshVisibleSelection()
        }

        private func refreshVisibleSelection() {
            guard let collectionView else { return }
            for item in collectionView.visibleItems() {
                guard let mediaItem = item as? MediaCollectionItem,
                      let path = collectionView.indexPath(for: mediaItem),
                      parent.items.indices.contains(path.item) else { continue }
                mediaItem.setSelection(parent.selection.contains(parent.items[path.item].id))
            }
        }
    }
}

private final class AdaptiveMediaLayout: NSCollectionViewLayout {
    private let gap: CGFloat = 14
    private let topInset: CGFloat = 4
    private let bottomInset: CGFloat = 16
    private var cachedAttributes: [IndexPath: NSCollectionViewLayoutAttributes] = [:]
    private var cachedContentSize = NSSize.zero
    private var cachedWidth: CGFloat = -1
    private var cachedHeight: CGFloat = -1
    private var cachedItemCount = -1

    override func prepare() {
        guard let collectionView else { return }
        let width = max(1, collectionView.enclosingScrollView?.contentView.bounds.width ?? collectionView.bounds.width)
        let height = collectionView.enclosingScrollView?.contentView.bounds.height ?? 0
        let count = collectionView.numberOfItems(inSection: 0)
        guard abs(width - cachedWidth) > 0.5 || abs(height - cachedHeight) > 0.5 || count != cachedItemCount else { return }

        let columns: CGFloat
        if width >= 700 { columns = 3 }
        else if width >= 450 { columns = 2 }
        else { columns = 1 }
        let cardWidth = floor((width - gap * (columns - 1)) / columns)
        let cardHeight = floor(cardWidth * 2 / 3) + 50
        let columnCount = Int(columns)
        cachedAttributes.removeAll(keepingCapacity: true)
        for item in 0..<count {
            let row = item / columnCount
            let column = item % columnCount
            let indexPath = IndexPath(item: item, section: 0)
            let attributes = NSCollectionViewLayoutAttributes(forItemWith: indexPath)
            attributes.frame = NSRect(
                x: CGFloat(column) * (cardWidth + gap),
                y: topInset + CGFloat(row) * (cardHeight + gap),
                width: cardWidth,
                height: cardHeight
            )
            cachedAttributes[indexPath] = attributes
        }
        let rows = count == 0 ? 0 : Int(ceil(Double(count) / Double(columnCount)))
        let requiredHeight = rows == 0 ? 0 : topInset + CGFloat(rows) * cardHeight + CGFloat(rows - 1) * gap + bottomInset
        cachedContentSize = NSSize(width: width, height: max(height, requiredHeight))
        cachedWidth = width
        cachedHeight = height
        cachedItemCount = count
    }

    override var collectionViewContentSize: NSSize { cachedContentSize }

    override func layoutAttributesForItem(at indexPath: IndexPath) -> NSCollectionViewLayoutAttributes? {
        cachedAttributes[indexPath]
    }

    override func layoutAttributesForElements(in rect: NSRect) -> [NSCollectionViewLayoutAttributes] {
        cachedAttributes.values.filter { $0.frame.intersects(rect) }
    }

    override func shouldInvalidateLayout(forBoundsChange newBounds: NSRect) -> Bool {
        abs(newBounds.width - cachedWidth) > 0.5 || abs(newBounds.height - cachedHeight) > 0.5
    }
}

private final class MediaGridScrollView: NSScrollView {
    weak var collectionView: NSCollectionView?
    private var previousClipSize = NSSize.zero

    override func layout() {
        super.layout()
        guard let collectionView else { return }
        let size = contentView.bounds.size
        let width = size.width
        guard width > 0, size != previousClipSize else { return }
        previousClipSize = size
        if abs(collectionView.frame.width - width) > 0.5 { collectionView.frame.size.width = width }
        collectionView.collectionViewLayout?.invalidateLayout()
    }
}

fileprivate final class NativeMediaCollectionView: NSCollectionView {
    var onSelectionChanged: (() -> Void)?
    private var selectionOverlay: MediaRubberBandView?
    var selectionAnchor: IndexPath?

    override var acceptsFirstResponder: Bool { true }

    override func keyDown(with event: NSEvent) {
        if event.modifierFlags.contains(.command), event.charactersIgnoringModifiers?.lowercased() == "a" {
            selectionIndexPaths = Set((0..<numberOfItems(inSection: 0)).map { IndexPath(item: $0, section: 0) })
            onSelectionChanged?()
            return
        }
        if event.keyCode == 53 {
            selectionIndexPaths = []
            selectionAnchor = nil
            onSelectionChanged?()
            return
        }
        super.keyDown(with: event)
    }

    override func mouseDown(with event: NSEvent) {
        let start = convert(event.locationInWindow, from: nil)
        guard event.type == .leftMouseDown else { super.mouseDown(with: event); return }
        window?.makeFirstResponder(self)
        if let path = indexPathForItem(at: start) {
            if event.modifierFlags.contains(.shift) {
                let anchor = selectionAnchor ?? selectionIndexPaths.min(by: { $0.item < $1.item }) ?? path
                let range = min(anchor.item, path.item)...max(anchor.item, path.item)
                let expanded = Set(range.map { IndexPath(item: $0, section: path.section) })
                selectionIndexPaths = event.modifierFlags.contains(.command) ? selectionIndexPaths.union(expanded) : expanded
                onSelectionChanged?()
            } else if event.modifierFlags.contains(.command) {
                var selected = selectionIndexPaths
                if selected.contains(path) { selected.remove(path) } else { selected.insert(path) }
                selectionIndexPaths = selected
                selectionAnchor = path
                onSelectionChanged?()
            } else {
                super.mouseDown(with: event)
                selectionAnchor = path
            }
            return
        }
        performRubberBandSelection(start: start, event: event)
    }

    private func performRubberBandSelection(start: NSPoint, event: NSEvent) {
        let retainsExisting = event.modifierFlags.contains(.command)
        let base = retainsExisting ? selectionIndexPaths : []
        if !retainsExisting { selectionIndexPaths = [] }
        let overlay = MediaRubberBandView(frame: .zero)
        overlay.wantsLayer = true
        addSubview(overlay)
        selectionOverlay = overlay

        var current = start
        while let next = window?.nextEvent(matching: [.leftMouseDragged, .leftMouseUp]) {
            current = convert(next.locationInWindow, from: nil)
            let rectangle = NSRect(x: min(start.x, current.x), y: min(start.y, current.y), width: abs(start.x - current.x), height: abs(start.y - current.y))
            overlay.frame = rectangle
            _ = autoscroll(with: next)
            let hit = Set((collectionViewLayout?.layoutAttributesForElements(in: rectangle) ?? []).compactMap { attributes -> IndexPath? in
                attributes.representedElementCategory == .item ? attributes.indexPath : nil
            })
            selectionIndexPaths = base.union(hit)
            onSelectionChanged?()
            if next.type == .leftMouseUp { break }
        }
        overlay.removeFromSuperview()
        selectionOverlay = nil
        onSelectionChanged?()
    }
}

private final class MediaRubberBandView: NSView {
    override func draw(_ dirtyRect: NSRect) {
        NSColor.controlAccentColor.withAlphaComponent(0.16).setFill()
        dirtyRect.fill()
        NSColor.controlAccentColor.withAlphaComponent(0.9).setStroke()
        let border = NSBezierPath(rect: bounds.insetBy(dx: 0.5, dy: 0.5))
        border.lineWidth = 1
        border.stroke()
    }
}

private final class MediaCollectionItem: NSCollectionViewItem {
    static let reuseIdentifier = NSUserInterfaceItemIdentifier("GlassTVMediaCollectionItem")
    private let tile = MediaTileView()

    override func loadView() { view = tile }

    func configure(
        media: LocalPhoto,
        selected: Bool,
        onSelectionToggle: @escaping () -> Void,
        onToggleRotation: @escaping () -> Void,
        onMove: @escaping (Int) -> Void
    ) {
        tile.configure(media: media, selected: selected, onSelectionToggle: onSelectionToggle, onToggleRotation: onToggleRotation, onMove: onMove)
    }

    func setSelection(_ selected: Bool) { tile.setSelection(selected) }
}

private final class MediaTileView: NSView {
    private let artwork = MediaArtworkView()
    private let titleLabel = NSTextField(labelWithString: "")
    private let detailLabel = NSTextField(labelWithString: "")
    private let selectionButton = NSButton()
    private let videoBadge = NSVisualEffectView()
    private let videoIcon = NSImageView()
    private let durationLabel = NSTextField(labelWithString: "")
    private var trackingArea: NSTrackingArea?
    private var isSelected = false
    private var isHovering = false
    private var onSelectionToggle: (() -> Void)?
    private var onToggleRotation: (() -> Void)?
    private var onMove: ((Int) -> Void)?

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        layer?.cornerRadius = 14
        layer?.masksToBounds = false
        setAccessibilityElement(true)
        setAccessibilityRole(.group)

        artwork.wantsLayer = true
        artwork.layer?.cornerRadius = 14
        artwork.layer?.masksToBounds = true
        addSubview(artwork)

        selectionButton.bezelStyle = .regularSquare
        selectionButton.isBordered = false
        selectionButton.title = ""
        selectionButton.imagePosition = .imageOnly
        selectionButton.imageScaling = .scaleProportionallyDown
        selectionButton.target = self
        selectionButton.action = #selector(toggleSelection)
        selectionButton.wantsLayer = true
        selectionButton.layer?.cornerRadius = 13
        selectionButton.layer?.borderWidth = 1
        selectionButton.contentTintColor = .white
        selectionButton.alphaValue = 0.65
        selectionButton.setAccessibilityRole(.checkBox)
        addSubview(selectionButton)

        videoBadge.material = .hudWindow
        videoBadge.blendingMode = .withinWindow
        videoBadge.state = .active
        videoBadge.wantsLayer = true
        videoBadge.layer?.cornerRadius = 10
        videoBadge.layer?.masksToBounds = true
        videoBadge.addSubview(videoIcon)
        videoBadge.addSubview(durationLabel)
        addSubview(videoBadge)

        titleLabel.font = .systemFont(ofSize: 13, weight: .medium)
        titleLabel.lineBreakMode = .byTruncatingTail
        detailLabel.font = .systemFont(ofSize: 11)
        detailLabel.textColor = .secondaryLabelColor
        addSubview(titleLabel)
        addSubview(detailLabel)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func layout() {
        super.layout()
        let imageHeight = floor(bounds.width * 2 / 3)
        artwork.frame = NSRect(x: 0, y: bounds.height - imageHeight, width: bounds.width, height: imageHeight)
        selectionButton.frame = NSRect(x: 10, y: artwork.frame.maxY - 36, width: 26, height: 26)
        videoBadge.frame = NSRect(x: 10, y: artwork.frame.minY + 10, width: max(78, durationLabel.intrinsicContentSize.width + 38), height: 22)
        videoIcon.frame = NSRect(x: 7, y: 4, width: 14, height: 14)
        durationLabel.frame = NSRect(x: 25, y: 3, width: videoBadge.bounds.width - 30, height: 16)
        titleLabel.frame = NSRect(x: 2, y: 22, width: bounds.width - 4, height: 18)
        detailLabel.frame = NSRect(x: 2, y: 3, width: bounds.width - 4, height: 16)
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let trackingArea { removeTrackingArea(trackingArea) }
        let area = NSTrackingArea(rect: bounds, options: [.activeInKeyWindow, .mouseEnteredAndExited, .inVisibleRect], owner: self, userInfo: nil)
        addTrackingArea(area)
        trackingArea = area
    }

    override func mouseEntered(with event: NSEvent) { isHovering = true; updateSelectionAppearance() }
    override func mouseExited(with event: NSEvent) { isHovering = false; updateSelectionAppearance() }

    func configure(
        media: LocalPhoto,
        selected: Bool,
        onSelectionToggle: @escaping () -> Void,
        onToggleRotation: @escaping () -> Void,
        onMove: @escaping (Int) -> Void
    ) {
        self.onSelectionToggle = onSelectionToggle
        self.onToggleRotation = onToggleRotation
        self.onMove = onMove
        titleLabel.stringValue = media.name
        detailLabel.stringValue = media.inRotation ? "In rotation" : "Stored only"
        setAccessibilityLabel(media.name)
        setAccessibilityHelp("\(media.mediaKind == .video ? "Video" : "Photo"). \(detailLabel.stringValue).")
        artwork.image = NSImage(contentsOf: media.mediaKind == .video ? VideoProcessor.previewURL(for: media) : ImageProcessor.url(for: media))
        artwork.fallbackImage = centralIcon(named: media.mediaKind == .video ? "movies" : "image")
        videoBadge.isHidden = media.mediaKind != .video
        if media.mediaKind == .video {
            videoIcon.image = centralIcon(named: "movies")
            durationLabel.stringValue = formattedDuration(media.durationSeconds)
        }
        setSelection(selected)
        menu = makeMenu(inRotation: media.inRotation)
    }

    func setSelection(_ selected: Bool) {
        isSelected = selected
        setAccessibilitySelected(selected)
        selectionButton.state = selected ? .on : .off
        selectionButton.setAccessibilityLabel("\(selected ? "Deselect" : "Select") \(titleLabel.stringValue)")
        selectionButton.setAccessibilityValue(selected ? 1 : 0)
        updateSelectionAppearance()
    }

    private func updateSelectionAppearance() {
        layer?.borderWidth = isSelected ? 2 : 0
        layer?.borderColor = isSelected ? NSColor.controlAccentColor.cgColor : NSColor.clear.cgColor
        selectionButton.alphaValue = (isSelected || isHovering) ? 1 : 0.65
        selectionButton.image = isSelected ? centralIcon(named: "check") : nil
        selectionButton.layer?.backgroundColor = (isSelected ? NSColor.controlAccentColor : NSColor.black.withAlphaComponent(0.42)).cgColor
        selectionButton.layer?.borderColor = (isSelected ? NSColor.controlAccentColor : NSColor.white.withAlphaComponent(0.7)).cgColor
    }

    @objc private func toggleSelection() { onSelectionToggle?() }
    @objc private func toggleRotation() { onToggleRotation?() }
    @objc private func moveEarlier() { onMove?(-1) }
    @objc private func moveLater() { onMove?(1) }

    private func makeMenu(inRotation: Bool) -> NSMenu {
        let menu = NSMenu()
        let rotation = NSMenuItem(title: inRotation ? "Remove from rotation" : "Include in rotation", action: #selector(toggleRotation), keyEquivalent: "")
        rotation.target = self
        menu.addItem(rotation)
        menu.addItem(.separator())
        let earlier = NSMenuItem(title: "Move earlier", action: #selector(moveEarlier), keyEquivalent: "")
        earlier.target = self
        menu.addItem(earlier)
        let later = NSMenuItem(title: "Move later", action: #selector(moveLater), keyEquivalent: "")
        later.target = self
        menu.addItem(later)
        return menu
    }
}

private final class MediaArtworkView: NSView {
    var image: NSImage? { didSet { needsDisplay = true } }
    var fallbackImage: NSImage? { didSet { needsDisplay = true } }

    override func draw(_ dirtyRect: NSRect) {
        guard let image = image ?? fallbackImage else { return }
        let source = image.size
        guard source.width > 0, source.height > 0 else { return }
        let scale = max(bounds.width / source.width, bounds.height / source.height)
        let size = NSSize(width: source.width * scale, height: source.height * scale)
        let frame = NSRect(x: (bounds.width - size.width) / 2, y: (bounds.height - size.height) / 2, width: size.width, height: size.height)
        image.draw(in: frame, from: .zero, operation: .sourceOver, fraction: 1, respectFlipped: true, hints: [.interpolation: NSImageInterpolation.high])
    }
}

private func centralIcon(named name: String) -> NSImage? {
    let image = Bundle.main.url(forResource: name, withExtension: "png", subdirectory: "icons").flatMap(NSImage.init(contentsOf:))
    image?.isTemplate = false
    return image
}

private func formattedDuration(_ seconds: Double?) -> String {
    guard let seconds, seconds.isFinite, seconds >= 0 else { return "Video" }
    let whole = Int(seconds.rounded(.down))
    return String(format: "%d:%02d", whole / 60, whole % 60)
}
