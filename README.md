# CollapseRx1

[![][travis img]][travis]
[![][license img]][license]

## Communication

- [GitHub Issues](https://github.com/jstuyts/CollapseRx1/issues)

## What does it do?

#### Concurrency

Parallel execution. Concurrency aware request caching. Automated batching through request collapsing.

## Binaries

Change history and version numbers => [CHANGELOG.md](https://github.com/jstuyts/CollapseRx1/blob/master/CHANGELOG.md)

Example for Maven:

```xml
<dependency>
    <groupId>com.javathinker</groupId>
    <artifactId>collpaserx1</artifactId>
    <version>x.y.z</version>
</dependency>
```
and for Ivy:

```xml
<dependency org="com.javathinker" name="collapserx1" rev="x.y.z" />
```

You need Java 8 or later.

## Build

To build:

```
$ git clone git@github.com:jstuyts/CollapseRx1.git
$ cd CollapseRx1/
$ ./gradlew build
```

## Bugs and Feedback

For bugs, questions and discussions please use the [GitHub Issues](https://github.com/jstuyts/CollapseRx1/issues).

 
## LICENSE

Copyright 2013 Netflix, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

<http://www.apache.org/licenses/LICENSE-2.0>

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.


[travis]:https://travis-ci.org/jstuyts/CollapseRx1
[travis img]:https://travis-ci.org/jstuyts/CollapseRx1.svg?branch=master

[release]:https://github.com/jstuyts/CollapseRx1/releases
[release img]:https://img.shields.io/github/release/jstuyts/CollapseRx1.svg

[license]:LICENSE-2.0.txt
[license img]:https://img.shields.io/badge/License-Apache%202-blue.svg

