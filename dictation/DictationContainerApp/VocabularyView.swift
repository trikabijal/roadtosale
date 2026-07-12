import SwiftUI
import DictationCoreBase

// MARK: - Model

/// The user's custom word library, persisted durably in the App Group (survives bundle renames — the
/// same store the Mac now uses). Shared with `RecordSession`, which reads it per dictation to force the
/// right spelling/casing of names + jargon during cleanup.
@MainActor
final class VocabularyModel: ObservableObject {
    @Published private(set) var terms: [String] = []
    private let store: VocabularyStore?

    init() {
        store = (try? VocabularyStore.iOSURL()).map { VocabularyStore(url: $0) }
        if let store { terms = Vocabulary.ensureSeeded(store) }
    }

    func add(_ raw: String) {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty,
              !terms.contains(where: { $0.caseInsensitiveCompare(t) == .orderedSame }) else { return }
        terms.insert(t, at: 0)
        store?.save(terms)
    }

    func remove(at offsets: IndexSet) {
        terms.remove(atOffsets: offsets)
        store?.save(terms)
    }
}

// MARK: - View

struct WordsTab: View {
    @StateObject private var model = VocabularyModel()
    @State private var newWord = ""
    @FocusState private var fieldFocused: Bool

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Just Talk speaks the way you speak")
                            .font(.title3.weight(.semibold))
                        Text("Add names, jargon, and acronyms we should always get right — they're spelled correctly every time, even when the mic mishears.")
                            .font(.callout).foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                    .listRowBackground(Color.clear)
                }

                Section {
                    HStack(spacing: 10) {
                        TextField("Add a word or name…", text: $newWord)
                            .textInputAutocapitalization(.words)
                            .autocorrectionDisabled()
                            .focused($fieldFocused)
                            .onSubmit(commit)
                        Button(action: commit) {
                            Image(systemName: "plus.circle.fill").font(.title2)
                        }
                        .disabled(newWord.trimmingCharacters(in: .whitespaces).isEmpty)
                        .foregroundStyle(JTBrand.gold)
                    }
                }

                Section("Your words (\(model.terms.count))") {
                    if model.terms.isEmpty {
                        Text("No words yet. Add the terms you dictate often.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(model.terms, id: \.self) { Text($0) }
                            .onDelete(perform: model.remove)
                    }
                }
            }
            .navigationTitle("Words")
            .toolbar { EditButton() }
        }
    }

    private func commit() {
        model.add(newWord)
        newWord = ""
        fieldFocused = true
    }
}
