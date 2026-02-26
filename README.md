# Maven Silent Extension (MSE)

A Maven core extension that replaces Maven's verbose output with a dense,
machine-readable format. Designed for AI coding agents that operate within
finite context windows, where thousands of lines of lifecycle ceremony waste
tokens and bury actionable errors.

Silent on success. Precise on failure.

## How it works

MSE implements Maven's `EventSpy` API. When activated, it:

1. Suppresses Maven's default SLF4J logging (lifecycle banners, download
   progress, plugin headers).
2. Parses Surefire/Failsafe XML reports after test execution to extract
   structured failure details -- regardless of JVM forking.
3. Emits all output as `MSE:`-prefixed lines for unambiguous machine parsing.

## Requirements

- Java 11+
- Maven 3.6.x through 3.9.x

## Installation

Build the extension:

```
mvn package
```

Copy `target/maven-silent-extension-0.1.0-SNAPSHOT.jar` to a known location.

## Usage

Inject the extension at invocation time. No changes to `pom.xml` or
`.mvn/extensions.xml` are required.

```
MSE_ACTIVE=true mvn -Dmaven.ext.class.path=/path/to/maven-silent-extension.jar clean verify
```

Without `MSE_ACTIVE=true`, the extension is inert. The JAR can be left on the
classpath unconditionally.

## Output format

All lines are prefixed with `MSE:`.

Successful build:

```
MSE:SESSION_START modules=12 goals=clean,verify
MSE:OK modules=12 tests=342/342 time=47s
```

Test failure:

```
MSE:FAIL maven-surefire-plugin:test @ my-module
MSE:TESTS total=342 passed=339 failed=2 errors=1 skipped=0
MSE:TEST_FAIL com.example.AppTest#testParseInput
  expected:<42> but was:<0>
  at com.example.AppTest.testParseInput(AppTest.java:27)
MSE:TEST_ERROR com.example.ServiceTest#testConnection
  java.net.ConnectException: Connection refused
  at com.example.ServiceTest.testConnection(ServiceTest.java:18)
MSE:BUILD_FAILED failed=1 modules=12 tests=339/342 time=52s
```

Compiler error:

```
MSE:FAIL maven-compiler-plugin:compile @ my-module
MSE:ERR /src/main/java/com/example/App.java:10:15 cannot find symbol
MSE:ERR /src/main/java/com/example/App.java:20:1 ';' expected
MSE:BUILD_FAILED failed=1 modules=12 tests=0/0 compiler_errors=2 time=8s
```

Internal error (falls back to passthrough):

```
MSE:PASSTHROUGH <reason>
```

## Limitations

- Output from non-compiler, non-test plugins (exec-maven-plugin,
  frontend-maven-plugin) is not yet captured.
- Maven 4.x compatibility is untested.
- Plugins that fork arbitrary OS processes are out of scope.

## License

TBD