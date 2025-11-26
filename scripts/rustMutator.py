#!/usr/bin/env python3
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


import atexit
import shutil
import subprocess
import argparse
from pathlib import Path
import logging
import re
import random
from tqdm import tqdm
from typing import List, Tuple

from Shared import certoraUtils as Util

rust_mutator_logger = logging.getLogger("rust_mutator")

NUM_MUTANTS = 10
SEED = 1

def parse_args() -> argparse.Namespace:
    """
    Parse command-line arguments.

    Returns:
        argparse.Namespace: Parsed arguments containing source_file and mutant_dir.
    """
    parser = argparse.ArgumentParser(
        description=(
            "Run universalmutator on a Rust source file, generate mutants, "
            "and ensure the original file remains unchanged."
        )
    )
    parser.add_argument(
        "--file_to_mutate",
        "-f",
        type=Path,
        required=True,
        help="Path to the Rust source file to mutate (e.g., src/lib.rs)"
    )
    parser.add_argument(
        "--build_script",
        "-b",
        type=str,
        required=True,
        help = "Custom build command to execute for each mutant (e.g., 'cargo build --release')"
    )
    parser.add_argument(
        "--mutants_location",
        "-m",
        type=Path,
        default="mutantsDir",
        help="Directory to store generated mutants (e.g., mutants_output)"
    )
    parser.add_argument(
        "--num_mutants",
        "-n",
        type=int,
        default=NUM_MUTANTS,
        help=f"The upper bound on the number of mutants to generate (default: {NUM_MUTANTS})"
    )
    parser.add_argument(
        "--seed",
        "-s",
        type=int,
        default=SEED,
        help="Seed value for random selection to ensure repeatable testing"
    )
    parser.add_argument(
        "--debug",
        "-d",
        action="store_true",
        help="Enable debug logging"
    )
    return parser.parse_args()


def clean_temp_files(file_to_mutate: Path) -> None:
    """
    Remove temporary mutant output files matching the pattern '.um.mutant_output.*'.

    Args:
        file_to_mutate (Path): Path to the original source file.
    """
    temp_files = Path().rglob(".um.mutant_output.*")

    rust_mutator_logger.info("Removing temporary mutant output files...")
    for temp_file in temp_files:
        temp_file.unlink()
        rust_mutator_logger.info(f"Removed: {temp_file}")

    backup_file = next(Path().rglob(f"{file_to_mutate}.um.backup.*"), None)
    if backup_file:
        backup_file.unlink()
        rust_mutator_logger.info(f"Removed: {backup_file}")

    rust_mutator_logger.info("Temporary files removal completed.")


def setup_logger(debug: bool = False) -> None:
    """
    Set up the logger for the Rust Mutator script.

    Args:
        debug (bool): Enable debug logging.
    """
    logging.basicConfig(
        level=logging.DEBUG if debug else logging.INFO,
        format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
        handlers=[
            logging.StreamHandler()
        ]
    )


def uniform_selection(mutants: List[Path], num_select: int, seed: int) -> List[Path]:
    """
    Select a subset of mutants uniformly across the list using a seed.

    Args:
        mutants (List[Path]): List of mutants.
        num_select (int): Number of mutants to select.
        seed (int): Seed value for random selection.

    Returns:
        List[Path]: Selected mutants.
    """
    random.seed(seed)

    # Shuffle the mutants to randomize their order
    shuffled_mutants = mutants.copy()
    random.shuffle(shuffled_mutants)

    return shuffled_mutants[:num_select]


def validate_source_file(file_to_mutate: Path) -> None:
    """
    Validate that the source file exists and has a .rs extension.

    Args:
        file_to_mutate (Path): Path to the Rust source file.

    Raises:
        Util.CertoraUserInputError: If validation fails.
    """
    if not file_to_mutate.exists():
        raise Util.CertoraUserInputError(f"Source file '{file_to_mutate}' does not exist.")
    if not file_to_mutate.is_file():
        raise Util.CertoraUserInputError(f"Source file '{file_to_mutate}' is not a file.")
    if file_to_mutate.suffix != ".rs":
        raise Util.CertoraUserInputError(f"Source file '{file_to_mutate}' does not have a .rs extension.")


def validate_mutant_dir(mutants_location: Path) -> None:
    """
    Validate that the mutant directory exists and is empty. If the directory does not exist, create it.

    Args:
        mutants_location (Path): Directory to store generated mutants.

    Raises:
        Util.CertoraUserInputError: If validation fails.
    """
    if mutants_location.exists():
        rust_mutator_logger.debug(f"Mutant directory '{mutants_location}' already exists.")
        # Check if the directory is empty
        if any(mutants_location.iterdir()):
            raise Util.CertoraUserInputError(f"Mutant directory '{mutants_location}' is not empty.")
    else:
        mutants_location.mkdir(parents=True)
        rust_mutator_logger.info(f"Mutant directory '{mutants_location}' created successfully.")


def validate_mutant_count(file_to_mutate: Path, mutants_location: Path, num_mutants: int, seed: int) -> None:
    """
    Validate the number of mutants generated is less than or equal to the specified limit. If the number of mutants
    exceeds the limit, select uniformly based on the seed and delete the rest.

    Args:
        file_to_mutate (Path): Path to the original Rust source file.
        mutants_location (Path): Directory containing generated mutants.
        num_mutants (int): Upper bound on the number of mutants to generate.
        seed (int, optional): Seed value for random selection.

    Raises:
        Util.CertoraUserInputError: If no mutants are generated.
    """
    # Define the regex pattern to extract the mutant number
    pattern = re.compile(rf"{re.escape(file_to_mutate.stem)}\.mutant\.(\d+)\.rs$")

    # Gather all mutants matching the pattern
    mutants = [mutant for mutant in mutants_location.iterdir() if pattern.match(mutant.name)]

    num_mutants_generated = len(mutants)

    if num_mutants_generated == 0:
        raise Util.CertoraUserInputError("No mutants generated. Exiting...")

    rust_mutator_logger.info(f"Number of mutants generated: {num_mutants_generated}")

    # If number of mutants exceeds the limit, select uniformly based on seed
    if num_mutants_generated > num_mutants:
        selected_mutants = uniform_selection(mutants, num_mutants, seed)
        # Delete mutants not in selected_mutants
        mutants_to_delete = set(mutants) - set(selected_mutants)
        for mutant in mutants_to_delete:
            mutant.unlink()
        rust_mutator_logger.info(f"Selected {len(selected_mutants)} mutants based on seed {seed}. Deleted {len(mutants_to_delete)} mutants.")
    elif num_mutants_generated < num_mutants:
        rust_mutator_logger.warning(f"Number of mutants generated ({num_mutants_generated}) is less than the specified limit ({num_mutants}).")

    # Maintain the mutant file name incremental indexing after selection and deletion
    numbered_mutants: List[Tuple[Path, int]] = [
        (mutant, int(match.group(1)))
        for mutant in mutants_location.iterdir()
        if (match := pattern.match(mutant.name))
    ]
    for mutant, mutant_number in numbered_mutants:
        if not 0 <= mutant_number < num_mutants:
            for i in range(num_mutants):
                new_file_name = mutant.with_name(f"{file_to_mutate.stem}.mutant.{i}.rs")
                if new_file_name.exists():
                    continue
                mutant.rename(new_file_name)
                rust_mutator_logger.debug(f"Renamed {mutant} to {new_file_name}")
                break

def validate_mutate_command() -> None:
    """
    Validate that the universalmutator command is available in the PATH.

    Raises:
        Util.CertoraUserInputError: If validation fails.
    """
    if shutil.which("mutate") is None:
        raise Util.CertoraUserInputError("Currently for Rust projects, universalmutator is required to generate mutants. "
                                         "The universalmutator command 'mutate' not found in PATH. "
                                         "To install universalmutator, please follow the instructions at"
                                         " https://github.com/agroce/universalmutator")


def run_mutate(file_to_mutate: Path, mutants_location: Path, build_command: str) -> None:
    """
    Execute the universalmutator command to generate mutants, displaying a progress bar.

    Args:
        file_to_mutate (Path): Path to the Rust source file.
        mutants_location (Path): Directory to store generated mutants.
        build_command (str): Command to execute for each mutant to verify compilation.

    Raises:
        subprocess.CalledProcessError: If the mutate command fails.
    """
    mutate_command = [
        "mutate",
        str(file_to_mutate),
        "rust",
        "--mutantDir",
        str(mutants_location),
        "--cmd",
        build_command
    ]
    rust_mutator_logger.info("Generating mutants...")
    rust_mutator_logger.debug(f"Running universalmutator with command: {' '.join(mutate_command)}")

    # Initialize the subprocess with real-time output capture
    process = subprocess.Popen(
        mutate_command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        universal_newlines=True,
        bufsize=1
    )

    total_mutants = None
    mutant_pattern = re.compile(r"^PROCESSING MUTANT: (\d+):")
    total_pattern = re.compile(r"^(\d+) MUTANTS GENERATED BY RULES")
    valid_mutant_pattern = re.compile(rf"VALID \[written to {mutants_location.stem}/{file_to_mutate.stem}\.mutant\.\d+\.rs\]")

    progress_bar = None

    try:
        if not process.stdout:
            rust_mutator_logger.error("Failed to capture output from the mutate command.")
            raise subprocess.CalledProcessError(1, mutate_command)

        for line in process.stdout:
            line = line.strip()
            rust_mutator_logger.debug(line)

            # Parse total number of mutants from the initial output
            if total_mutants is None:
                total_match = total_pattern.match(line)
                if total_match:
                    total_mutants = int(total_match.group(1))
                    progress_bar = tqdm(total=total_mutants, desc="Mutants Generated", unit="mutant")
                    rust_mutator_logger.info(f"Total mutants generated: {total_mutants}")
                    continue

            # Update progress bar for each processed mutant
            mutant_match = mutant_pattern.match(line)
            if mutant_match and progress_bar:
                progress_bar.update(1)
                continue

            # Optionally, handle validation messages
            if valid_mutant_pattern.search(line):
                rust_mutator_logger.info(f"Valid mutant generated: {line}")

        process.wait()

        if progress_bar:
            progress_bar.close()

        if process.returncode != 0:
            rust_mutator_logger.error(f"Mutation generation failed with return code {process.returncode}")
            raise subprocess.CalledProcessError(process.returncode, mutate_command)

        rust_mutator_logger.info("Mutation generation completed successfully.")

    except Exception as e:
        if progress_bar:
            progress_bar.close()
        rust_mutator_logger.error(f"An error occurred during mutation generation: {e}")
        raise
    finally:
        if process and process.poll() is None:
            process.terminate()

def run_universal_mutator(
        file_to_mutate: Path,
        build_script: str,
        mutants_location: Path,
        num_mutants: int = NUM_MUTANTS,
        seed: int = SEED,
        debug: bool = False
) -> None:
    f"""
    Generate mutants for the specified source file and ensure the original file remains unchanged.

    Args:
        file_to_mutate (Path): Path to the Rust source file.
        build_script (str): Command to execute for each mutant to verify compilation.
        mutants_location (Path): Directory to store generated mutants.
        num_mutants (int): Upper bound on the number of mutants to generate (default: {NUM_MUTANTS}).
        seed (int): Seed value for random selection to ensure repeatable testing (default: {SEED}).
        debug (bool): Enable debug logging.

    Raises:
        Util.CertoraUserInputError: If any validation fails.
        Exception: For unexpected errors.
    """
    backup_file = None
    build_command = f"cp MUTATE & {build_script}"

    atexit.register(clean_temp_files, file_to_mutate)

    try:

        # Set up the logger
        setup_logger(debug)

        # Validate mutate command is available
        validate_mutate_command()

        # Validate source file
        validate_source_file(file_to_mutate)

        # Backup the original source file
        rust_mutator_logger.info(f"Backing up '{file_to_mutate}' ...")
        backup_file = Util.create_backup(file_to_mutate)

        if backup_file:
            atexit.register(Util.restore_backup, backup_file)
            rust_mutator_logger.info(f"Backing up '{file_to_mutate}' to '{backup_file} succeeded")
        else:
            rust_mutator_logger.warning(f"Backing up '{file_to_mutate}' to '{backup_file} failed")

        # Validate or create mutant directory
        validate_mutant_dir(mutants_location)

        # Run the mutation command
        run_mutate(file_to_mutate, mutants_location, build_command)

        # Make sure the number of mutants generated is less than or equal to the specified limit
        validate_mutant_count(file_to_mutate, mutants_location, num_mutants, seed)
    finally:
        if backup_file:
            Util.restore_backup(backup_file)
        clean_temp_files(file_to_mutate)


if __name__ == "__main__":
    # Parse command-line arguments
    args = parse_args()
    run_universal_mutator(
        args.file_to_mutate,
        args.build_script,
        args.mutants_location,
        args.num_mutants,
        args.seed,
        args.debug
    )
