# Mojo for IntelliJ IDEA

Native Mojo language support for IntelliJ Platform IDEs.

## Features

- Parsing and PSI for Mojo source files.
- Syntax and semantic highlighting.
- Configurable colors under **Settings | Editor | Color Scheme | Mojo**.
- Highlighting for declarations, calls, parameters, fields, decorators, and built-in types.
- Toggle line comments with `#`.
- Automatic pairs for brackets and single, double, and triple quotes.
- TODO indexing in comments.

## Install a development build

Build the plugin:

```shell
./gradlew buildPlugin
```

In the IDE, open **Settings | Plugins**, choose **Install Plugin from Disk**, and select the ZIP file from `build/distributions`.

## Development

Requirements:

- JDK 21
- IntelliJ IDEA 2025.2

Run an IDE with the plugin installed:

```shell
./gradlew runIde
```

Run tests:

```shell
./gradlew test
```

Regenerate the parser after changing `Mojo.bnf`:

```shell
./gradlew generateParser
```

## Limitations

Name resolution, navigation, completion, formatting, inspections, and run configurations are not implemented yet. Semantic highlighting currently uses syntax information only.

## License

MIT License. See [LICENSE](LICENSE).
