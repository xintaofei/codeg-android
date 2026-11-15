# Third-party notices

## Project artwork

Agent avatars use project-owned, text-only monograms and a generic application
palette. This repository does not redistribute third-party agent logos or brand
artwork.

## Gradle Wrapper

The Gradle Wrapper scripts and `gradle-wrapper.jar` are generated from the
[Gradle project](https://github.com/gradle/gradle) and are distributed under the
Apache License 2.0. Their existing source notices are retained.

## Product names and marks

This application uses third-party product names and visual marks only to
identify software and services with which it interoperates. Those names and
marks remain the property of their respective owners. Their inclusion does not
imply affiliation, sponsorship, or endorsement of this project.

The Apache License 2.0 applies to copyrightable project material; it does not
grant permission to use third-party trade names, trademarks, service marks, or
product names except as required for reasonable and customary description of
the origin of the work and reproduction of notices.

## Bundled Mermaid 11.17.2

The app ships the published `dist/mermaid.min.js` of
[Mermaid](https://github.com/mermaid-js/mermaid) 11.17.2, unmodified, as
`app/src/main/assets/mermaid/mermaid.min.js`. It draws ```` ```mermaid ```` blocks
in chat, off-screen and offline. Mermaid is licensed under the MIT License; its
license text is `config/licenses/mermaid-LICENSE.txt` and is included in the
generated notice bundle. The bundle also carries the notices of the libraries it
is built from in its own comments. See `config/licenses/EMBEDDED_COMPONENTS.md`
for the component record.

## Embedded Public Suffix List data

OkHttp 4.12.0 contains `publicsuffixes.gz`, compiled from the
[Public Suffix List](https://publicsuffix.org/list/public_suffix_list.dat). That
data is licensed under the Mozilla Public License 2.0. The exact source snapshot
used by OkHttp 4.12.0 is available in its
[tagged source tree](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/test/resources/okhttp3/internal/publicsuffix/public_suffix_list.dat).

The generated notice bundle includes OkHttp's notice and the full MPL-2.0 text.
See `config/licenses/EMBEDDED_COMPONENTS.md` for the reviewed component record.

## Dependencies

Runtime and build dependencies retain their own licenses. Direct dependency
coordinates and pinned versions are declared in `gradle/libs.versions.toml`.
The checked-in
[release-runtime dependency inventory](third-party-licenses/DEPENDENCIES.md)
records the resolved artifacts, versions, declared licenses, project URLs, and
embedded license files. Supplemental records cover licensed content embedded
inside an otherwise differently licensed artifact and artifact-specific license
decisions. The report reads each artifact's immediate POM rather than unioning
licenses from broader parent projects; the rationale for Jakarta Inject 2.0.1
is recorded in `config/licenses/LICENSE_REVIEW.md`. The complete notice bundle
is packaged with the application and can be opened from Settings. Regenerate it
with:

```bash
./gradlew :app:syncThirdPartyLicenseReport --no-build-cache --rerun-tasks
```

Dependencies retain their original copyright notices and license terms.
Downstream distributors remain responsible for verifying the report against the
exact artifacts they ship.
