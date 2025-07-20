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

import json
import logging
import os
import shutil
from pathlib import Path
from typing import Dict, Any, Set, List, Tuple, Optional

from CertoraProver.certoraMiniSpecParser import SpecImportLexer, SpecImportParser, SpecWithImports
from CertoraProver.certoraContextClass import CertoraContext
from Shared import certoraUtils as Util

build_logger = logging.getLogger("build_conf")


class CertoraVerifyGenerator:
    def __init__(self, context: CertoraContext):
        self.context = context
        self.certora_verify_struct: Dict[str, Any] = {}
        self.verify_spec = None
        self.verify_contract: Optional[str] = None
        # cannot have both --verify and --assert so we try each one
        if context.verify is not None:
            verification_query = context.verify

            vq_contract, vq_spec = verification_query.split(":", 2)
            vq_spec = Util.abs_posix_path(vq_spec)  # get full abs path, will need to update later to .certora_sources

            vq_spec_with_imports: SpecWithImports = self.get_spec_with_imports(vq_spec)
            self.verify_contract = vq_contract
            self.verify_spec = vq_spec_with_imports
            self.copy_specs()
            # we need to build once because of the early typechecking...
            self.update_certora_verify_struct(False)

        elif self.context.equivalence_contracts is not None:
            if self.context.method is None:
                raise Util.CertoraUserInputError("Argument `method` is required for equivalence checks")
            if len(self.context.method) != 1:
                raise Util.CertoraUserInputError("Can only check equivalence on one method")
            equivalence_query = context.equivalence_contracts
            equiv_contracts = equivalence_query.split("=")
            self.certora_verify_struct = {
                "type": "equivalence",
                "primary_contract": equiv_contracts[0],
                "secondary_contract": equiv_contracts[1]
            }
        elif context.is_concord_app:
            if len(context.contracts) != 2:
                raise Util.CertoraUserInputError(f"Expecting 2 contracts but got {len(context.contracts)}: {context.contracts}")
            contract_list = list(context.contracts)
            self.certora_verify_struct = {
                "type": "equivalence",
                "primary_contract": contract_list[0],
                "secondary_contract": contract_list[1]
            }

    def update_certora_verify_struct(self, in_certora_sources: bool) -> None:
        """
        Updates the struct that .certora_verify.json is based on.
        @param in_certora_sources - whether all paths should be rooted relatively to
                                    .certora_sources.
                                    This is set to true before we actually call the Prover,
                                    and before that - local typechecking - we don't care.
        """
        if self.context.verify is not None:
            assert self.verify_contract is not None and self.verify_spec is not None

            vq_contract, vq_spec_with_imports = self.verify_contract, self.verify_spec

            def path_updater(path: str) -> str:
                if in_certora_sources:
                    res = Util.find_filename_in(Util.get_certora_sources_dir(), path)
                    assert res is not None, f"Couldn't find file {path} in {Util.get_certora_sources_dir()}"
                    return res
                else:
                    return path

            vq_spec = path_updater(vq_spec_with_imports.spec_file)
            self.certora_verify_struct = {"type": "spec",
                                          "primary_contract": vq_contract,
                                          "specfile": vq_spec,
                                          "specfileOrigRelpath": Util.as_posix(os.path.relpath(vq_spec)),
                                          "specfilesToImportDecls": {path_updater(k): list(v) for k, v in
                                                                     vq_spec_with_imports.spec_files_to_orig_imports
                                                                     .items()},
                                          "importFilesToOrigRelpaths":
                                              {path_updater(k): str(Path(k).relative_to(Path().resolve())) for k in
                                               vq_spec_with_imports.abspath_imports_to_locs
                                               .keys()}
                                          }

    def get_spec_with_imports(self, spec_file: str) -> SpecWithImports:
        seen_abspath_imports_to_locs: Dict[str, Set[str]] = dict()
        spec_file_to_orig_imports: Dict[str, Set[str]] = dict()
        self.check_and_collect_imported_spec_files(Path(spec_file), seen_abspath_imports_to_locs, [spec_file],
                                                   spec_file_to_orig_imports)
        # will start with spec_idx == 0 to give the imports the unique names starting with 1
        return SpecWithImports(spec_file, 0, seen_abspath_imports_to_locs, spec_file_to_orig_imports)

    def check_and_collect_imported_spec_files(self, spec_file: Path, seen_abspath_imports_to_locs: Dict[str, Set[str]],
                                              dfs_stack: List[str],
                                              spec_file_to_orig_imports: Dict[str, Set[str]]) -> None:
        with spec_file.open() as f:
            spec_content = f.read()
            spec_import_lexer = SpecImportLexer(spec_file, spec_content)
            spec_import_parser = SpecImportParser(spec_import_lexer)
            imports_with_locs = spec_import_parser.parse(spec_import_lexer.tokenize(spec_content))

            if imports_with_locs:
                spec_file_to_orig_imports[str(spec_file)] = set()
                for orig_import_to_loc in imports_with_locs:
                    spec_file_to_orig_imports[str(spec_file)].add(orig_import_to_loc[0])

            build_logger.debug(fr'In {spec_file}, found the imports: {imports_with_locs}')
            if spec_import_parser.parse_error_msgs:  # We have parsing errors
                errors_str = '\n'.join(spec_import_parser.parse_error_msgs)
                raise Util.CertoraUserInputError(f'Could not parse {spec_file} '
                                                 f'due to the following errors:\n{errors_str}')

            abspath_imports_with_locs = list(map(
                lambda path_to_loc: (Util.abs_posix_path_relative_to_root_file(Path(path_to_loc[0]), spec_file),
                                     path_to_loc[1]),
                imports_with_locs))

            invalid_imports_with_locs = [p for p in abspath_imports_with_locs if not os.path.isfile(p[0]) or
                                         os.path.splitext(p[0])[1] != '.spec']

            def path_to_loc_str(path_to_loc: Tuple[Path, str]) -> str:
                return f'{path_to_loc[1]}:\"{path_to_loc[0]}\"'

            if invalid_imports_with_locs:
                invalid_paths_str = '\n'.join(map(path_to_loc_str, invalid_imports_with_locs))
                raise Util.CertoraUserInputError(
                    f'In {spec_file}, the following import declarations do not import existing .spec files:'
                    f'\n{invalid_paths_str}\n'
                )

            for import_path_to_loc in abspath_imports_with_locs:  # Visit each import declaration in a DFS fashion
                if import_path_to_loc[0] in dfs_stack:  # We have cyclic imports :(((
                    imports_cycle = ' -->\n'.join(
                        dfs_stack[dfs_stack.index(str(import_path_to_loc[0])):] + [str(import_path_to_loc[0])])
                    raise Util.CertoraUserInputError(
                        f'In {spec_file}, the import declaration {path_to_loc_str(import_path_to_loc)} '
                        f'leads to an imports\' cycle:\n{imports_cycle}')

                import_loc_with_spec_file = f'{spec_file}:{import_path_to_loc[1]}'

                # xxx condition should cast import_path_to_loc[0] here - apply after gaining more confidence
                # (and it looks like we do not have much confidence in this code at all)
                if import_path_to_loc[0] in seen_abspath_imports_to_locs:  # Visit each import declaration only once
                    seen_abspath_imports_to_locs[str(import_path_to_loc[0])].add(import_loc_with_spec_file)
                    continue

                seen_abspath_imports_to_locs[str(import_path_to_loc[0])] = {import_loc_with_spec_file}
                dfs_stack.append(str(import_path_to_loc[0]))
                self.check_and_collect_imported_spec_files(Path(import_path_to_loc[0]), seen_abspath_imports_to_locs,
                                                           dfs_stack, spec_file_to_orig_imports)
                dfs_stack.pop()

    def copy_specs(self) -> None:
        spec = self.verify_spec
        assert spec is not None
        Path(spec.eventual_path_to_spec).parent.mkdir(parents=True, exist_ok=True)
        build_logger.debug(f"copying spec file {spec.spec_file} to "
                           f"{Util.abs_posix_path(spec.eventual_path_to_spec)}")
        shutil.copy2(spec.spec_file, spec.eventual_path_to_spec)
        #  copy .spec imports
        for import_srcpath, import_dstpath in spec.abspath_to_eventual_import_paths.items():
            shutil.copy2(import_srcpath, import_dstpath)

    def get_spec_files(self) -> Set[Path]:
        specs: Set[Path] = set()
        if self.verify_spec is not None:
            spec = self.verify_spec
            path = Path(spec.spec_file).resolve()
            if path.exists():
                specs.add(path)
            for import_srcpath, import_dstpath in spec.abspath_to_eventual_import_paths.items():
                path = Path(import_srcpath).resolve()
                if path.exists():
                    specs.add(path)
        return specs

    def dump(self) -> None:
        build_logger.debug(f"writing {Util.abs_posix_path(Util.get_certora_verify_file())}")
        with Util.get_certora_verify_file().open("w+") as output_file:
            json.dump(self.certora_verify_struct, output_file, indent=4, sort_keys=True)
