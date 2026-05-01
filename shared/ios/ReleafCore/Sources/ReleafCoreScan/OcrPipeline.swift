/*
 * OcrPipeline.swift
 *
 * Multi-page parallel OCR over the `OcrEngine` contract. Sits on top
 * of an injected engine and runs recognition on N page URLs with
 * bounded concurrency, emitting per-page results as an `AsyncStream`
 * so callers can drive a "page X of Y" progress UI.
 *
 * Mirror of `OcrPipeline.kt` in `:shared:scan`. Per QUICKINK_PROPOSAL.md
 * §6, multi-page parallel OCR is an explicit Phase 3 piece sitting
 * atop the engine, separate from the engine itself so a future
 * cloud-OCR engine drops in unchanged.
 *
 * Design:
 *
 *   - Stream output (`AsyncStream<PageOcr>`) instead of a one-shot
 *     return, so callers can update progress UI as pages complete.
 *     The stream emits exactly `imageURLs.count` elements before
 *     finishing; elements arrive in *completion* order, not input
 *     order — caller indexes by `PageOcr.pageIndex`.
 *
 *   - Bounded concurrency via index-based throttling inside a
 *     `TaskGroup`. Default 3 concurrent recognitions, tunable via
 *     the `concurrency` constructor parameter. Vision is CPU-bound;
 *     unbounded concurrency thrashes the engine on multi-page docs.
 *
 *   - Per-page failure ≠ batch failure. One page's `OcrError` lands
 *     as `PageOcr.failure(pageIndex, error)`; the rest of the batch
 *     keeps running. Callers wanting "abort on first failure" can
 *     `break` out of their `for await` loop — the stream's
 *     `onTermination` cancels the producing task and remaining
 *     pages don't start.
 *
 *   - Cancellation: when the consuming task is cancelled, the
 *     stream's `onTermination` cancels the producing task, which
 *     propagates down the `TaskGroup`. Pages that have already
 *     started a Vision `perform` call finish that page on the
 *     background queue (Vision's API has no cancellation hook).
 *     Pages that haven't started don't. So a cancel mid-batch
 *     wastes at most `concurrency` pages worth of CPU.
 */

import Foundation

public struct OcrPipeline: Sendable {

    public let engine: OcrEngine
    public let concurrency: Int

    public init(engine: OcrEngine, concurrency: Int = 3) {
        precondition(concurrency >= 1, "concurrency must be ≥ 1")
        self.engine = engine
        self.concurrency = concurrency
    }

    /// Stream of per-page results. Emits exactly `imageURLs.count`
    /// elements before finishing. Each element is a `PageOcr` carrying
    /// the input page index alongside either a successful `OcrResult`
    /// or the `OcrError` that page hit. Pass `imageURLs` in the input
    /// order you want indexed by; the stream emits in completion order
    /// across the batch.
    public func recognizePages(_ imageURLs: [URL]) -> AsyncStream<PageOcr> {
        let engine = self.engine
        let concurrency = self.concurrency
        return AsyncStream { continuation in
            let task = Task {
                await withTaskGroup(of: Void.self) { group in
                    var nextIndex = 0

                    func spawnNext() {
                        guard nextIndex < imageURLs.count else { return }
                        let index = nextIndex
                        let url   = imageURLs[index]
                        nextIndex += 1
                        group.addTask {
                            await Self.processOne(
                                index:        index,
                                url:          url,
                                engine:       engine,
                                continuation: continuation
                            )
                        }
                    }

                    // Prime up to `concurrency` concurrent workers.
                    let initialCount = min(concurrency, imageURLs.count)
                    for _ in 0..<initialCount {
                        spawnNext()
                    }

                    // Each completion → spawn the next page's worker.
                    // `for await _ in group` waits on whichever task
                    // finishes next, regardless of input order, so
                    // the throttle stays at exactly `concurrency`
                    // in-flight without a semaphore.
                    for await _ in group {
                        spawnNext()
                    }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Internals

    private static func processOne(
        index: Int,
        url: URL,
        engine: OcrEngine,
        continuation: AsyncStream<PageOcr>.Continuation
    ) async {
        do {
            let result = try await engine.recognize(imageURL: url)
            continuation.yield(.success(pageIndex: index, result: result))
        } catch is CancellationError {
            // Stream is being torn down. Don't yield a stale failure;
            // the consumer has already moved on.
            return
        } catch let error as OcrError {
            continuation.yield(.failure(pageIndex: index, error: error))
        } catch {
            // Engine impls SHOULD throw `OcrError`, but if any third-
            // party engine ships in the future and throws something
            // else, fold it onto the closest case so the pipeline's
            // contract stays predictable.
            continuation.yield(.failure(
                pageIndex: index,
                error: .recognitionFailed(message: error.localizedDescription)
            ))
        }
    }
}

/// One page's OCR outcome. `pageIndex` matches the caller's input
/// array — caller maps back to the original page URL via that index.
public enum PageOcr: Sendable {
    case success(pageIndex: Int, result: OcrResult)
    case failure(pageIndex: Int, error: OcrError)

    public var pageIndex: Int {
        switch self {
        case .success(let i, _): return i
        case .failure(let i, _): return i
        }
    }
}
