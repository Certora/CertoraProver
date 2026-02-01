# Concordance

Concordance is a script that rewrites complex internal function using an LLM
and Concord to verify the rewrites.

## Requirements

Install the packages listed in `requirements.txt`. You will also need a local installation
of the Certora prover (cloud runs are not yet supported). Unlike the rest of the certora
ecosystem, Concordance requires version Python >=3.11.

Finally, this script interacts with the Claude API, and you will need an API key. Before
running the script ensure that `ANTHROPIC_API_KEY` is set in your environment.

## Running the script

The basic usage is via the wrapper script:

`../certoraConcordance.py ./file`

Where `./file` is a file that contains an internal function body that you want rewritten.
There are various other command line options to tweak the parameters/models used when invoking the LLM;
see `concordance.py --help`.

As mentioned above, this script only works if it can run the certora prover locally on your
machine. This restriction is likely to be lifted in the future, but in the meantime, follow
the instructions to get a working local copy of the prover.
