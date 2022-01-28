#!/usr/bin/env bash
set -x
awslocal kinesis create-stream --stream-name test --shard-count 1
awslocal kinesis put-record --stream-name test --data foo --partition-key test
awslocal kinesis put-record --stream-name test --data bar --partition-key test
awslocal kinesis put-record --stream-name test --data baz --partition-key test
set +x
