#!/usr/bin/env bash

if test $(basename `pwd`) = "bin"
then
		cd ..
fi

FILE="conf.edn"

if ! test -e $FILE
	 then
		cat << EOF > $FILE
{:elastic-url "http://localhost:9200"
 :port        3003
 :dev-mode    true}
EOF
fi

docker run -d -p 9200:9200 -p 9300:9300 -e "discovery.type=single-node" elasticsearch:7.1.0

# I'll use minio to spoof S3 locally for storing figures, but that isn't necessary yet.
# docker run -d -p 9000:9000 minio/minio server /data
