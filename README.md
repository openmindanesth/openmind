# Openmind

[Try out the demo!](https://openmind-eu.herokuapp.com)

Note, to create extracts, you must have, or create, an
[Orcid](https://orcid.org) account. No login is required to search.

Feedback is welcome. Please feel free to create an issue.

The is still an early iteration. Take a look at the [mockups](idea.pdf) for the
bigger idea.

## Development Mode

The script `bin/dev-setup.sh` will start docker containers for web containers
and generate a config file to access them if one isn't already present.

Now start a repl in your favourite editor, wait for the js to compile, and run
`(openmind.server/init!)` to start the webserver.

To run the app standalone, you need to have the clj command line tools
installed. Compile the javascript with

```
clj -e cljs.main --optimizations advanced --output-to "resources/public/cljs-out/dev-main.js -c openmind.core
```

And run the server with

```
clj -e openmind.server
```

From the command line.

That's it. Go to http://localhost:3003 in your browser to see the page.

Note that when running a local elastic instance, data stored will be deleted
when the container is terminated.

# License

Copyright © 2020 Henning Reimann & Thomas Getgood

Distributed under the Eclipse Public License either version 1.0 or (at your
option) any later version.
