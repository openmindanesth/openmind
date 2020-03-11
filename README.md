# Openmind

[Try out the demo!](https://openmind.macroexpanse.com)

Note, to create extracts, you must have, or create, an
[Orcid](https://orcid.org) account. No login is required to search.

Feedback is welcome. Please feel free to create an issue.

The is still an early iteration. Take a look at the [mockups](idea.pdf) for the
bigger idea.

## Development Mode

Steps:

1. Start Elasticsearch. Either use the script `dev/setup.sh`, or start your own.
2. Copy `example.conf.edn` to `conf.edn` in the root dir. Edit the config
   variables to point to your Elasticsearch cluster, your S3 bucket (the default
   one is publicly readable, so feel free to leave it if you're just working on
   the front end), and your AWS credentials if you're using your own S3 bucket.
3. Start the clj/cljs repls in your favourite editor. In emacs that's just a
   matter of `cider-jack-in-clj&cljs`.
4. In the clj repl, load the `openmind.server` namespace and run `(init!)` to
   start the server.
5. To initialise a local ElasticSearch instance, run
   `(openmind.elastic/init-cluster!)`.
6. There is a dump of example es data in `dev/es-data.edn` which can be loaded
   via `(openmind.elastic/restore!  "dev/es-data.edn")`.
6. Navigate to http://localhost:3003 in your browser (if you didn't change the
   `:port` config in `conf.edn`.
7. Have at it.


# License

Copyright © 2020 Henning Reimann & Thomas Getgood

Distributed under the Eclipse Public License either version 1.0 or (at your
option) any later version.
