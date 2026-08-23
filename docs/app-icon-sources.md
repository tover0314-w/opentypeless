# App icon policy

OpenTypeless does not distribute third-party brand-logo images for application context labels.
The earlier WebP reference set was removed because a source URL or a copy from an installed
application did not, by itself, establish redistribution permission.

The desktop UI now displays the application or service name alongside a generic context-family
glyph from the licensed `lucide-react` dependency. This keeps app recognition useful without
implying affiliation or bundling unverified trademark artwork.

Do not recreate `src/assets/app-icons/reference` or add third-party logo artwork unless the change
also records all of the following in this document:

- the exact upstream asset and immutable source revision;
- the copyright holder and asset-specific license or written redistribution permission;
- any applicable brand-usage conditions; and
- the shipped file's SHA-256 digest.

The CI and release workflows intentionally reject the legacy reference directory. Changing that
guard requires an explicit legal-material review; adding a dependency license is not sufficient to
grant trademark rights.
