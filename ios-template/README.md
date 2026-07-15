# Shroud iOS app — SwiftUI, MVVM, design-system tokens via `Theme`.

## Layout

| Path | Role |
| --- | --- |
| `Shroud/App/` | `@main` entry, app lifecycle |
| `Shroud/Features/` | Screen views + view models (1:1 with `design/iOS-App.pen`) |
| `Shroud/Services/` | Network, crypto, storage (protocol-backed) |
| `ShroudUI/Theme/` | Design tokens — colors, typography |
| `ShroudUI/Components/` | Reusable SwiftUI components |
| `ShroudTests/` | Unit tests |

## Open in Xcode

```bash
open ios/Shroud.xcodeproj
```

Scheme: **Shroud** · Deployment target: **iOS 26.0**

## API client

`APIClient` targets `http://127.0.0.1:8080/api/v1` in Debug (simulator). Error bodies decode to `APIError` matching the server `AppError` envelope.
