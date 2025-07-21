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
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Set, Optional

from Crypto.Hash import keccak

from CertoraProver.certoraContext import collect_args_build_cache_affecting, get_attr_value, \
    collect_args_build_cache_disabling, get_client_version
from CertoraProver.certoraContextClass import CertoraContext
from Shared import certoraUtils as Util
from Shared.certoraUtils import safe_create_dir

build_cache_logger = logging.getLogger("build_cache")


@dataclass
class CachedFiles:
    certora_build_file: Path
    all_contract_files: Set[Path]
    build_output_props_file: Path
    may_store_in_build_cache: bool

    # to keep .post_autofinders
    path_with_additional_included_files: Path

    def all_exist(self, main_cache_entry_dir: Path, sub_cache_key: str) -> bool:
        exist = self.certora_build_file.exists() and \
            self.certora_build_file == main_cache_entry_dir / f"{sub_cache_key}.{CachedFiles.certora_build_suffix}"
        exist = exist and (main_cache_entry_dir / f"{sub_cache_key}.{CachedFiles.certora_build_suffix}").exists()
        exist = exist and self.build_output_props_file.exists() and \
            self.build_output_props_file == main_cache_entry_dir / \
            f"{sub_cache_key}.{CachedFiles.build_output_props_suffix}"

        return exist

    certora_build_suffix = "certora_build.json"
    file_list_suffix = "file_list.json"
    build_output_props_suffix = "build_output_props.json"
    additional_included_files = "additional_included_files"


class CertoraBuildCacheManager:

    @staticmethod
    def build_from_cache(context: CertoraContext) -> Optional[CachedFiles]:
        """
        Given a certora context, tries to get a matching .certora_build.json file from the build cache.
        @returns None if no cache hit, otherwise - the matching `.certora_build.json` file, and a set of source files
        """
        build_cache_dir = Util.get_certora_build_cache_dir()
        main_cache_entry_dir = build_cache_dir / context.main_cache_key
        if not main_cache_entry_dir.exists():
            build_cache_logger.info(f"cache miss on build cache key {context.main_cache_key}")
            return None

        # here's the tricky matching part:
        # each file list in the main cache dir may or may not match
        # a change in file list necessitates also a change in imports
        # so a different file list may match on the same main cache key
        # therefore we try to find a match on one of the file lists in the matching main cache key folder
        sub_cache_key = None
        all_contract_files = None
        file_list_files = list(main_cache_entry_dir.glob(f"*.{CachedFiles.file_list_suffix}"))
        for file_list_file in file_list_files:
            # no '.' in sub_cache_keys, expecting a hex string
            sub_cache_key_from_saved_entry = file_list_file.stem.split(".")[0]

            # now read the specified files and check if their hash matches
            with open(file_list_file, 'r') as file_list_handle:
                file_list = json.load(file_list_handle)
                sub_cache_key_current_for_list = CertoraBuildCacheManager.get_sub_cache_key(file_list)
                if sub_cache_key_current_for_list is None:
                    build_cache_logger.debug(f"Current sub build cache key computed for {file_list_file} is invalid")
                    continue
                elif sub_cache_key_current_for_list == sub_cache_key_from_saved_entry:
                    build_cache_logger.info(f"We have a match on build cache key {context.main_cache_key} and "
                                            f"{sub_cache_key_current_for_list}")
                    sub_cache_key = sub_cache_key_current_for_list
                    all_contract_files = set([Path(f) for f in file_list])
                    break
                else:
                    build_cache_logger.debug(f"Current sub build cache key computed for {file_list_file} is a miss")
                    continue

        if sub_cache_key is None:
            build_cache_logger.info("All sub-cache-key file list files missed, cache miss on build cache key "
                                    f"{context.main_cache_key}")
            return None

        # cache hit
        certora_build_file = main_cache_entry_dir / f"{sub_cache_key}.{CachedFiles.certora_build_suffix}"
        if not certora_build_file.exists():
            build_cache_logger.warning(f"Got a cache-hit on build cache key {context.main_cache_key} and {sub_cache_key} "
                                       "but .certora_build.json file does not exist")
            return None

        if all_contract_files is None:  # should not be feasible
            build_cache_logger.warning(f"Got a cache-hit on build cache key {context.main_cache_key} and "
                                       f"{sub_cache_key} but file list file does not exist")
            return None

        build_output_props_file = main_cache_entry_dir / f"{sub_cache_key}.{CachedFiles.build_output_props_suffix}"
        if not build_output_props_file.exists():
            build_cache_logger.warning(f"Got a cache-hit on build cache key {context.main_cache_key} and {sub_cache_key} "
                                       f"but {CachedFiles.build_output_props_suffix} file does not exist")
            return None

        additional_included_files = main_cache_entry_dir / f"{sub_cache_key}.{CachedFiles.additional_included_files}"
        return CachedFiles(certora_build_file, all_contract_files, build_output_props_file,
                           may_store_in_build_cache=True, path_with_additional_included_files=additional_included_files)

    @staticmethod
    def save_build_cache(context: CertoraContext, cached_files: CachedFiles) -> None:
        build_cache_dir = Util.get_certora_build_cache_dir()
        main_cache_entry_dir = build_cache_dir / context.main_cache_key
        sub_cache_key = CertoraBuildCacheManager.get_sub_cache_key(cached_files.all_contract_files)
        if sub_cache_key is None:
            build_cache_logger.warning(f"Cannot save cache for main build cache key {context.main_cache_key} "
                                       "as sub-cache-key could not be computed")
            return

        if main_cache_entry_dir.exists():
            build_cache_logger.info(f"main build cache key already exists {context.main_cache_key}, "
                                    f"saving sub build cache key {sub_cache_key}")
            if cached_files.all_exist(main_cache_entry_dir, sub_cache_key):
                build_cache_logger.debug("cache already saved under this build cache key, override")
            else:
                build_cache_logger.debug(f"cache was corrupted, need to re-save build cache key "
                                         f"{context.main_cache_key} and sub cache key {sub_cache_key}")
            CertoraBuildCacheManager.save_files(cached_files, main_cache_entry_dir,
                                                sub_cache_key)
        else:
            build_cache_logger.info(f"saving main build cache key {context.main_cache_key} and sub cache key "
                                    f"{sub_cache_key}")
            safe_create_dir(main_cache_entry_dir)
            CertoraBuildCacheManager.save_files(cached_files, main_cache_entry_dir,
                                                sub_cache_key)

    @staticmethod
    def save_files(cached_files: CachedFiles, main_cache_entry_dir: Path,
                   sub_cache_key: str) -> None:
        # save .certora_build.json
        shutil.copyfile(cached_files.certora_build_file,
                        main_cache_entry_dir / f"{sub_cache_key}.{CachedFiles.certora_build_suffix}")
        # save file list
        with (main_cache_entry_dir / f"{sub_cache_key}.{CachedFiles.file_list_suffix}")\
             .open("w+") as file_list_file:
            json.dump([str(f) for f in cached_files.all_contract_files],
                      file_list_file,
                      indent=4,
                      sort_keys=True)
        # save additional props file
        shutil.copyfile(cached_files.build_output_props_file,
                        main_cache_entry_dir / f"{sub_cache_key}.{CachedFiles.build_output_props_suffix}")
        # save .post_autofinders from additional included files
        trg_path_with_additional_included_files = \
            main_cache_entry_dir / f"{sub_cache_key}.{CachedFiles.additional_included_files}"
        post_autofinder_dirs = list(
            cached_files.path_with_additional_included_files.glob(f"{Util.POST_AUTOFINDER_BACKUP_DIR}.*"))
        for post_autofinder_dir in post_autofinder_dirs:
            trg = trg_path_with_additional_included_files / post_autofinder_dir.name
            if post_autofinder_dir != trg:
                if post_autofinder_dir.is_dir():
                    if trg.exists():
                        shutil.rmtree(trg)
                    Util.safe_copy_folder(post_autofinder_dir, trg, shutil.ignore_patterns())
                else:
                    # highly unlikely .post_autofinder.[digit] will be a file and not a directory,
                    # but would rather not crash and future-proof instead
                    # (more likely, we may include other types of files and not just autofinder directories, in which
                    # case we'll change the variable name accordingly)
                    shutil.copyfile(post_autofinder_dir, trg)

    @staticmethod
    def get_main_cache_key(context: CertoraContext) -> str:
        cache_affecting_args = collect_args_build_cache_affecting(context)
        cache_affecting_args_dict = {}
        for arg in cache_affecting_args:
            attr = get_attr_value(context, arg)
            if isinstance(attr, list):
                attr = sorted(attr)
            elif isinstance(attr, dict):
                attr = dict(sorted(attr.items()))
            cache_affecting_args_dict[arg.get_conf_key()] = attr
        # add the package version string
        # there's some risk that between local versions we'll have collisions and false hits, but devs should know
        # what they're doing.
        if not Util.get_package_and_version()[0]:  # not installed
            build_cache_logger.warning("Build cache is used with a local certora-cli script version. " +
                                       "Beware of false hits and unexpected behaviors!")
        cache_affecting_args_dict["package_version_for_build_cache"] = get_client_version()

        # hash the dict with all cache-affecting keys
        to_hash_dict = dict(sorted(cache_affecting_args_dict.items()))
        build_cache_logger.debug(f"main build cache key computed based on {to_hash_dict}")
        buffer_to_hash = json.dumps(to_hash_dict)
        return CertoraBuildCacheManager.hash_string(buffer_to_hash)

    @staticmethod
    def get_sub_cache_key(all_contract_files: Set[Path]) -> Optional[str]:
        all_contract_files_sorted = sorted(all_contract_files, key=lambda fp: str(fp))
        file_hashes = []
        for f in all_contract_files_sorted:
            fp = Path(f)
            if not Path(f).exists():
                build_cache_logger.info(f"{f} does not exist, cannot compute sub build cache key")
                return None
            file_hash = CertoraBuildCacheManager.hash_file_contents(fp)
            file_hashes.append(file_hash)
        buffer_to_hash = ','.join(file_hashes)
        return CertoraBuildCacheManager.hash_string(buffer_to_hash)

    @staticmethod
    def hash_file_contents(f: Path) -> str:
        """
        @param f: the file to hash contents of. Assumed to exist and be ascii
        @return the hash of the contents of f
        """
        with f.open() as f_obj:
            bytes = f_obj.read()
            computed_hash = CertoraBuildCacheManager.hash_string(bytes)

        return computed_hash

    @staticmethod
    def hash_string(s: str) -> str:
        hash_obj = keccak.new(digest_bits=256)
        hash_obj.update(str.encode(s))
        return hash_obj.hexdigest()

    @staticmethod
    def cache_is_applicable(context: CertoraContext) -> bool:
        cache_disabling_args = collect_args_build_cache_disabling(context)
        return len(cache_disabling_args) == 0

    @staticmethod
    def cache_disabling_options(context: CertoraContext) -> str:
        cache_disabling_args = collect_args_build_cache_disabling(context)
        return ', '.join([attr.get_conf_key() for attr in cache_disabling_args])
