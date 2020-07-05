# Openmind

[Try out the demo!](https://openmind.macroexpanse.com)

Note, to create extracts, you must have, or create, an
[Orcid](https://orcid.org) account. No login is required to search.

Feedback is welcome. Please feel free to create an issue.

This is still an early iteration. Take a look at the [abstract](OpenMindIntro.pdf) for the
bigger idea.

## Development Mode

Steps:

1. Copy `example.conf.edn` to `conf.edn` in the root dir. Edit the config
   variables to point to your Elasticsearch cluster, your S3 bucket (the default
   one is publicly readable, so feel free to leave it if you're just working on
   the front end), and your AWS credentials if you're using your own S3 bucket.
2. Start Elasticsearch. The script `dev/setup.sh` will start an Elasticsearch
   instance in a docker container and populate it from S3 if `s3-data-bucket` is
   configured in `conf.edn`.
3. Start the clj/cljs repls in your favourite editor. In emacs that's just a
   matter of `cider-jack-in-clj&cljs`.
4. In the clj repl, load the `openmind.server` namespace and run `(init!)` to
   start the server.
6. Navigate to http://localhost:3003 in your browser (if you didn't change the
   `:port` config in `conf.edn`.
7. Have at it.

# Acknowledgements

OpenMind was born as an intellectual child of the MRathon, an hackathon for open
science worldwide that was hosted in 2019 in Montreal as a satellite event to
the ISMRM. We thank the organizers for bringing the team behind OpenMind
together and the participants for their support and invaluable feedback.

# License

Copyright © 2020 Henning Reimann & Thomas Getgood

Distributed under the Eclipse Public License either version 1.0 or (at your
option) any later version.
