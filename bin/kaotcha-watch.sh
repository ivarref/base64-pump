#!/usr/bin/env bash

printf "./bin/kaocha\ntests.edn\ntest/com/github/ivarref/kaocha_hook.clj\ndeps.edn" | entr -r ./bin/kaocha
