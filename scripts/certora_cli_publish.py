"""
*
*     The Certora Prover
*     Copyright (C) 2025 Certora Ltd.
*
*     This program is free software: you can redistribute it and/or modify
*     it under the terms of the GNU General Public License as published by
*     the Free Software Foundation, version 3 of the License.
*
*     This program is distributed in the hope that it will be useful,
*     but WITHOUT ANY WARRANTY, without even the implied warranty of
*     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
*     GNU General Public License for more details.
*
*     You should have received a copy of the GNU General Public License
*     along with this program.  If not, see <https://www.gnu.org/licenses/>.
*
"""

# to be run from scripts/ directory in CI
import json
from os import getenv, makedirs, listdir, remove
from datetime import datetime
import twine.commands.upload  # type: ignore
from twine.settings import Settings  # type: ignore
from setuptools import sandbox  # type: ignore
from shutil import copy, copytree, rmtree
from pathlib import Path
import argparse
import os


# license text
LICENSE_TEXT = """
The Certora Prover
Copyright (C) 2025 Certora Ltd.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, version 3 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY, without even the implied warranty of
MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
"""

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument('--local_install', default=False, action='store_true', help='To install locally.')
    parser.add_argument('--type_checker_path', default=None, help='Typechecker required to install locally.')
    parser.add_argument('--pip_command', default='pip3', help='The pip command to use with --local_install.')

    args = parser.parse_args()

    return args


# inputs we're going to write to enable package creation
CHANGELOG_FILE = Path("CHANGELOG.md")
PACKAGE_FILE = Path("PACKAGE.txt")
VERSION_FILE = Path("VERSION.txt")
LICENSE_FILE = Path("LICENSE")
MANIFEST_FILE = Path("MANIFEST.in")
README_FILE = Path("README.md")
SETUP_PY_FILE = Path("setup.py")
CERTORA_CLI_VERSION_METADATA = Path("CERTORA-CLI-VERSION-METADATA.json")  # consider importing from certoraUtils.py

if __name__ == '__main__':
    args = parse_args()
    if args.local_install:
        local_install = Path('local_install')
        local_install.mkdir(exist_ok=True)
        os.chdir(local_install)
    # git parameters and timestamp
    branch = getenv("CIRCLE_BRANCH")
    tag = getenv("CIRCLE_TAG")
    triggering_job = getenv("CIRCLE_JOB")

    if args.local_install:
        branch = "local"
        triggering_job = "local"
    if branch and not tag:
        BRANCH = branch.replace("/", "-")
    elif tag and not branch:
        TAG = tag
    else:
        raise Exception("we should have either branch or tag")

    commit = getenv("CIRCLE_SHA1")
    if args.local_install:
        commit = "last"
    elif commit is None:
        raise Exception("no commit")
    COMMIT = commit[0:7]

    if triggering_job is None:
        raise Exception("no triggering job")

    # for fast check testing version compatibility see https://pypi.org/project/pep440/
    TIMESTAMP = datetime.now().strftime("%Y%m%d.%-H.%-M.%f")  # this is compatible with PEP440

    # username and password for PyPi prod
    USERNAME = getenv("PRODPYPI_USER")
    PASSWORD = getenv("PRODPYPI_PASSWORD")

    # release name
    if not args.local_install:
        if tag:
            if triggering_job == "release_cli":
                NAME = "certora-cli"
            elif triggering_job == "release_cli_beta":
                NAME = "certora-cli-beta"
            elif triggering_job == "release_cli_beta_mirror":
                NAME = "certora-cli-beta-mirror"
            VERSION = f"{TAG}"
        else:
            if triggering_job != "release_cli_alpha":
                raise Exception(
                    f"Can release certora-cli-alpha-{BRANCH} only from release_cli_alpha, "
                    "tried to run from {triggering_job}")
            NAME = f"certora-cli-alpha-{BRANCH}"
            VERSION = f"{TIMESTAMP}+{COMMIT}"
        URL = f"https://pypi.org/project/{NAME}"
    else:
        # not uploaded only use for local install
        NAME = "certora-cli"
        VERSION = f"{TIMESTAMP}+{COMMIT}"
        URL = f"https://pypi.org/project/{NAME}"

    MIN_PYTHON_VERSION = '3.9'

    # Read dependencies from the requirements file
    REQUIREMENTS_FILE = (Path(__file__).parent / "certora_cli_requirements.txt")
    with REQUIREMENTS_FILE.open() as file:
        required_python_packages = [line.strip() for line in file if line.strip()]

    # copy relevant files to package structure
    CERTORA_CLI_DIR = Path("certora_cli")
    CERTORA_JARS = Path("certora_jars")
    CERTORA_BINS = Path("certora_bins")
    DEFAULT_DIR = Path.cwd().parent
    SCRIPTS = Path(__file__).parent

    EQ_DIR = Path("EquivalenceCheck")
    EQ_MC_TEMPLATE = EQ_DIR / "Eq_mc_template.spec"
    EQ_MC_NO_OUT_TEMPLATE = EQ_DIR / "Eq_mc_no_out_template.spec"
    EQ_TEMPLATE = EQ_DIR / "Eq_template.spec"
    EQ_DEFAULT_CONF = EQ_DIR / "Eq_default.conf"
    EQ_SANITY_CONF = EQ_DIR / "Eq_sanity.conf"
    EQ_SCRIPT = EQ_DIR / "equivCheck.py"
    EQ_SANITY = EQ_DIR / "sanity.spec"

    GAMBIT_BIN_LINUX = DEFAULT_DIR / "gambit-linux"
    GAMBIT_BIN_MACOS = DEFAULT_DIR / "gambit-macos"
    CERTORA_BINS_GAMBIT = CERTORA_BINS / "gambit"

    PLATFORMS = ['manylinux_2_28_x86_64', 'macosx_10_9_universal2']

    makedirs(CERTORA_CLI_DIR, exist_ok=True)
    makedirs(CERTORA_JARS, exist_ok=True)
    makedirs(CERTORA_BINS, exist_ok=True)
    copytree(SCRIPTS / "CertoraProver", CERTORA_CLI_DIR / "CertoraProver", dirs_exist_ok=True)
    copytree(SCRIPTS / "Mutate", CERTORA_CLI_DIR / "Mutate", dirs_exist_ok=True)
    copytree(SCRIPTS / EQ_DIR, CERTORA_CLI_DIR / EQ_DIR, dirs_exist_ok=True)
    copytree(SCRIPTS / "Shared", CERTORA_CLI_DIR / "Shared", dirs_exist_ok=True)
    copy(DEFAULT_DIR / "Typechecker.jar" if args.type_checker_path is None else args.type_checker_path, CERTORA_JARS)
    copy(DEFAULT_DIR / "ASTExtraction.jar", CERTORA_JARS)
    copy(SCRIPTS / "certoraRun.py", CERTORA_CLI_DIR)
    copy(SCRIPTS / "certoraMutate.py", CERTORA_CLI_DIR)
    copy(SCRIPTS / "certoraEqCheck.py", CERTORA_CLI_DIR)
    copy(SCRIPTS / "rustMutator.py", CERTORA_CLI_DIR)
    copy(SCRIPTS / "certoraSolanaProver.py", CERTORA_CLI_DIR)
    copy(SCRIPTS / "certoraSorobanProver.py", CERTORA_CLI_DIR)
    copy(SCRIPTS / "certoraEVMProver.py", CERTORA_CLI_DIR)
    copy(SCRIPTS / "certoraRanger.py", CERTORA_CLI_DIR)
    copy(SCRIPTS / "certoraConcord.py", CERTORA_CLI_DIR)
    copy(SCRIPTS / "certoraCVLFormatter.py", CERTORA_CLI_DIR)

    # write inputs
    INIT_PY = "__init__.py"
    for dir in [CERTORA_CLI_DIR, CERTORA_JARS, CERTORA_BINS]:
        with open(dir / INIT_PY, "w+") as initpy:
            initpy.write("")

    with CHANGELOG_FILE.open("w+") as log:
        if tag:
            log.write(f"# Release version {TAG} {COMMIT} {TIMESTAMP}")
        else:
            log.write(f"# Alpha version {BRANCH} {COMMIT} {TIMESTAMP}")

    with PACKAGE_FILE.open("w+") as package:
        package.write(NAME)

    with VERSION_FILE.open("w+") as version:
        version.write(VERSION)

    with LICENSE_FILE.open("w+") as license_file:
        license_file.write(LICENSE_TEXT)

    with MANIFEST_FILE.open("a") as manifest:
        manifest.write(f"recursive-include {CERTORA_JARS} *.jar {CERTORA_CLI_VERSION_METADATA}\n")
        manifest.write(f"recursive-include {CERTORA_BINS} gambit\n")
        manifest.write(f"recursive-include {CERTORA_CLI_DIR} {EQ_SCRIPT} {EQ_SANITY} {EQ_SANITY_CONF} \
                       {EQ_DEFAULT_CONF} {EQ_TEMPLATE} {EQ_MC_TEMPLATE} {EQ_MC_NO_OUT_TEMPLATE}")
        # platform specific files are added in 'build pypi packages' section

    with open(CERTORA_JARS / CERTORA_CLI_VERSION_METADATA, "w+") as metadata:
        tag_for_metadata_file = tag if tag else ""
        branch_for_metadata_file = BRANCH if branch else ""
        data = {"name": NAME, "tag": tag_for_metadata_file, "branch": branch_for_metadata_file, "commit": COMMIT,
                "timestamp": TIMESTAMP, "version": VERSION}
        json.dump(data, metadata)

    README_TEXT = f"Commit {COMMIT}. \
                   Build and Run scripts for executing the Certora Prover on Solidity smart contracts."

    with README_FILE.open("w+") as readme:
        readme.write(README_TEXT)

    with SETUP_PY_FILE.open("w+") as setup_py:
        setup_py.write(f"""
import setuptools

setuptools.setup(
    name="{NAME}",
    version="{VERSION if tag else TIMESTAMP}",
    author="Certora",
    author_email="support@certora.com",
    description="Runner for the Certora Prover",
    long_description="{README_TEXT}",
    long_description_content_type="text/markdown",
    url="{URL}",
    packages=setuptools.find_packages(),
    include_package_data=True,
    install_requires={required_python_packages},
    project_urls={{
        'Documentation': 'https://docs.certora.com/en/latest/',
        'Source': 'https://github.com/Certora/CertoraProver',
    }},
    license="GPL-3.0-only",
    classifiers=[
        "Programming Language :: Python :: 3",
        "License :: OSI Approved :: GNU General Public License v3 (GPLv3)",
        "Operating System :: OS Independent",
    ],
    entry_points={{
        "console_scripts": [
            "certoraRun = certora_cli.certoraRun:entry_point",
            "certoraMutate = certora_cli.certoraMutate:mutate_entry_point",
            "certoraEqCheck = certora_cli.certoraEqCheck:equiv_check_entry_point",
            "certoraSolanaProver = certora_cli.certoraSolanaProver:entry_point",
            "certoraSorobanProver = certora_cli.certoraSorobanProver:entry_point",
            "certoraEVMProver = certora_cli.certoraEVMProver:entry_point",
            "certoraRanger = certora_cli.certoraRanger:entry_point",
            "certoraCVLFormatter = certora_cli.certoraCVLFormatter:entry_point"
        ]
    }},
    python_requires='>={MIN_PYTHON_VERSION}',
)
""")

    # Build pypi packages
    if args.local_install:
        print("Install locally. Package won't be uploaded")
        os.system(f'{args.pip_command} install .')
        print('Installed locally, not uploading to PyPi.')
        exit(0)

    def cleanup() -> None:
        # clean previous build
        rmtree("build")
        # clean certora binaries
        for file in listdir(CERTORA_BINS):
            if not file == INIT_PY:
                remove(CERTORA_BINS / file)

    # source package and any platform wheel
    sandbox.run_setup('setup.py', ['sdist', 'bdist_wheel'])

    # specific platform wheels
    for plat_type in PLATFORMS:
        cleanup()
        if 'linux' in plat_type:
            copy(GAMBIT_BIN_LINUX, CERTORA_BINS_GAMBIT)
        elif 'macos' in plat_type:
            copy(GAMBIT_BIN_MACOS, CERTORA_BINS_GAMBIT)
        sandbox.run_setup('setup.py', ['bdist_wheel', '--plat-name'] + [plat_type])

    # Upload with twine https://twine.readthedocs.io/en/latest/internal/twine.commands.upload.html

    upload_settings = Settings(username=USERNAME, password=PASSWORD, verbose=True)

    dists = ["dist/*"]
    twine.commands.upload.upload(upload_settings, dists)
