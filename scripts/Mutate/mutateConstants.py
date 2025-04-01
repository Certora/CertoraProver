#     The Certora Prover
#     Copyright (C) 2025  Certora Ltd.
#
#     This program is free software: you can redistribute it and/or modify
#     it under the terms of the GNU General Public License as published by
#     the Free Software Foundation, version 3 of the License.
#
#     This program is distributed in the hope that it will be useful,
#     but WITHOUT ANY WARRANTY; without even the implied warranty of
#     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#     GNU General Public License for more details.
#
#     You should have received a copy of the GNU General Public License
#     along with this program.  If not, see <https://www.gnu.org/licenses/>.

import sys
from pathlib import Path
scripts_dir_path = Path(__file__).parent.resolve()
sys.path.insert(0, str(scripts_dir_path))
from Shared import certoraUtils as Util

VERIFICATION_PROGRESS = "verificationProgress"
TIMEOUT = "TIMEOUT"
UNKNOWN = "UNKNOWN"
SANITY_FAIL = "SANITY_FAIL"
CAUGHT = "CAUGHT"
UNCAUGHT = "UNCAUGHT"
RULES = "rules"
JOB_STATUS = "jobStatus"
JOB_DATA = "jobData"
PROGRESS = "progress"
ZIPOUTPUT = "zipOutput"
GENERALSTATE = "generalState"
PARAMS = "params"
OUTPUT = "output"
INPUTS = "inputs"
TARNAME = "TarName"
NAME = "name"
ID = "id"
COLLECT_SIGNED_URL = "preSignedUrl"
DIFF = "diff"
DESCRIPTION = "description"
ORIGINAL = "original"
MUTANTS = "mutants"
GAMBIT_MUTANT = "gambit_mutant"
GAMBIT_OUT = Path("gambit_out")
OUTDIR = "outdir"
FILENAME = "filename"
FILES = "files"
LINK = "link"
RULE_REPORT_LINK = "rule_report_link"
CERTORA_KEY = "certoraKey"
ANONYMOUS_KEY = "anonymousKey"
PRE_SIGNED_URL = "preSignedUrl"
STATUS = "status"
CHILDREN = "children"
NUM_MUTANTS = "num_mutants"
MSG = "msg"
TMP_GAMBIT_PATH = Path("tmp_gambit.gconf")
SOLC = "solc"
PACKAGES = "packages"
SOLC_REMAPPINGS = "solc_remappings"
SOLC_ALLOW_PATH = "solc_allow_path"
ZIP_PATH = Path("zip_output_certora_mutate.tar.gz")
CERTORA_MUTATE_SOURCES = Util.get_from_certora_internal(".certora_mutate_sources")
APPLIED_MUTANTS_DIR = Util.get_from_certora_internal("applied_mutants").resolve()
MUTANTS_COUNTER_DIR = APPLIED_MUTANTS_DIR / "mutation_counters"

SERVER = "server"
STAGING = "staging"
PRODUCTION = "production"
DEV = "vaas-dev"
CONF = "conf"
ORIG_RUN_PROVER_CONF = Path("cvt_conf_for_certoraMutate.conf")
PROVER_DOTCOM = "prover.certora.com"
STAGING_DOTCOM = "vaas-stg.certora.com"
DEV_DOTCOM = "vaas-dev.certora.com"
MUTATION_DASHBOARD_PRODUCTION = "https://prover.certora.com/mutations"
MUTATION_DASHBOARD_STAGING = "https://vaas-stg.certora.com/mutations"
MUTATION_DASHBOARD_DEV = "https://vaas-dev.certora.com/mutations"
MUTATION_TEST_REPORT_PRODUCTION = "mutation-testing.certora.com"
MUTATION_TEST_REPORT_STAGING = "mutation-testing-beta.certora.com"
MUTATION_TEST_REPORT_DEV = "mutation-testing-dev.certora.com"
OUTPUTJSON = "output.json"
REPORTS = "Reports"
DEFAULT_DUMP_FAILED_COLLECTS = Path("collection_failures.txt")
DEFAULT_COLLECT_FILE = Path("collect.json")
DEFAULT_POLL_TIMEOUT_IN_SECS = 30
DEFAULT_REQUEST_TIMEOUT_IN_SECS = 10
DEFAULT_MAX_TIMEOUT_ATTEMPTS_COUNT = 3
# Sets a file that will store the object sent to mutation testing UI (useful for testing)
DEFAULT_UI_OUT = Util.get_from_certora_internal("results.json")
SPLIT_STATS_DATA = "splitStatsdata.json"
DEFAULT_CSV_JOB_STATUS = "TIMEOUT/UNKNOWN"
ORIG_RUN = "orig_run"
SKIP_VALIDATE = 'skip_validate'
RUN_SOURCE = 'run_source'
CERTORA_SOURCES = '.certora_sources'
DEBUG = 'debug'
TEST = 'test'
GAMBIT_NO_OVERWRITE = 'no_overwrite'
SOURCEROOT = 'sourceroot'
RULENAME = "ruleName"
SOLC_OPTIMIZE = 'solc_optimize'
SOLC_OPTIMIZE_MAP = 'solc_optimize_map'
SOLC_EVM_VERSION = 'solc_evm_version'
SOLC_VIA_IR = 'solc_via_ir'
SOLC_EXPERIMENTAL_VIA_IR = 'solc_experimental_via_ir'

# conf keys
FILE_TO_MUTATE = 'file_to_mutate'
MUTANTS_LOCATION = 'mutants_location'
MUTATIONS = 'mutations'
GAMBIT = 'gambit'
MANUAL_MUTANTS = 'manual_mutants'
UNIVERSAL_MUTATOR = 'universal_mutator'
