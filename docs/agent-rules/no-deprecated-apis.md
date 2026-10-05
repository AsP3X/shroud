# No deprecated APIs

**Applies to:** every code change, on every platform.

Do not call, extend, or suppress a deprecated API, type, method, library, or language feature.
Search the change for `@Deprecated`, deprecation warnings, and `@Suppress` of `DEPRECATION` or
`OVERRIDE_DEPRECATION`.

- Replace each use with the current supported API.
- If the platform or library has no supported replacement, implement the behavior in the project.
- Do not add a new `@Deprecated` shim so callers can keep the old path.
- Do not silence a deprecation with `@Suppress` to leave the old call in place.

A method that is deprecated only from a newer SDK, while this repo's minimum SDK is lower, still
counts. Use the new API on versions that have it. On older versions, write the behavior without
that method when it can be done in-process. Keep a version check only when the operating system
exposes that behavior solely through the deprecated method and there is no in-process equivalent.
On those older versions, leave the compiler note. Do not suppress it.
