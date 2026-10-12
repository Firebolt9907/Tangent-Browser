# Tangent Reader 1.0.1 — source build instructions

This source package reproduces the Firefox add-on archive submitted to AMO. The build concatenates
readable JavaScript and embeds the stylesheet and bundled reader rules; it does not minify or
obfuscate the code and uses no third-party dependencies.

## Build environment

- Built on macOS with Node.js 26.0.0 and Info-ZIP 3.0.
- The build uses only Node.js built-in modules and the `zip` command. It also runs on Mozilla's
  Ubuntu reviewer environment with Node.js 24.14.0 and Info-ZIP 3.0.
- No `npm install`, network access, Android SDK, or Gradle build is needed.

## Reproduce the submitted add-on

From the root of this source package, run:

```sh
node extension/build.js stores
```

The Firefox upload archive is then at
`extension/dist/firefox/tangent-reader-1.0.1-firefox.xpi`. Its five files are `manifest.json`,
`background.js`, `reader.bundle.js`, `options.html`, and `options.js`. Compare those files with the
submitted XPI; ZIP metadata such as timestamps may differ.

`extension/build.js` copies the Firefox manifest and options files, combines the extension source
files into `reader.bundle.js`, and embeds compact rules from
`app/src/main/assets/reader_rules.json`. This source package includes all these inputs.
