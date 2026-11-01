# Android local conversation cache

Approved design: show cached session lists and the latest five user rounds first; refresh stale data after 60 seconds, always reconnect live sessions, and allow manual refresh. Persist authoritative API JSON only. Keep history pages isolated by snapshot, endpoint, profile and credential identity. Never replay transient approvals or optimistic messages from disk.

- [x] Add an atomic, bounded disk cache in the private cache directory, with request generations preventing writes after clear/delete/identity changes. Cover corruption, isolation, eviction and in-flight invalidation.
- [x] Cache raw list/detail/history responses through the saved-profile API client. Split large loaded windows into five-round/500-record pages; retain JSON fields, and invalidate old history pages on authoritative detail refresh.
- [x] Show cached lists/details immediately; fetch after 60 seconds or for live sessions. Manual refresh bypasses freshness and preserves displayed data, live streams and loaded history. Cache failures never fail a successful network request.
- [x] Add detail Refresh, cache timestamps/status, and Settings cache size/clear action. Keep paging LazyColumn indexes unchanged.
- [x] Run Android unit tests, Debug build and lint; verify diff and document usage. No backend changes or deployment.

Limits: 100 MiB globally and 10 MiB per conversation, LRU eviction; cache is disposable and excluded from backups. Unknown server content blocks remain intact in raw JSON. Existing structural transcript hash cannot prove text equality, so successful detail refresh replaces the snapshot and invalidates older cached pages.
