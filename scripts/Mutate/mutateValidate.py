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

from pathlib import Path
import sys
import logging

scripts_dir_path = Path(__file__).parent.resolve()
sys.path.insert(0, str(scripts_dir_path))

from Mutate import mutateAttributes as MutAttrs
import CertoraProver.certoraContextAttributes as Attrs
from Shared import certoraAttrUtil as AttrUtil
from Shared import certoraUtils as Util
from Shared import certoraValidateFuncs as Vf

from typing import Tuple, Any
from Mutate import mutateApp as App
from Mutate import mutateConstants as Constants

validation_logger = logging.getLogger("validation")

class MutateValidator:
    def __init__(self, mutate_app: App.MutateApp):
        self.mutate_app = mutate_app

    def validate(self) -> None:
        self.mutation_attribute_in_prover()
        self.validate_arg_types()
        self.validate_gambit_objs()
        self.validate_attribute_combinations()
        self.validate_manual_mutation()

    def validate_manual_mutation(self) -> None:
        if self.mutate_app.manual_mutants:
            if not isinstance(self.mutate_app.manual_mutants, list):
                raise Util.CertoraUserInputError("manual_mutants should be a list of objects")
            for mutant in self.mutate_app.manual_mutants:
                if not isinstance(mutant, dict):
                    raise Util.CertoraUserInputError(f"manual_mutants should be a list of objects, "
                                                     f"but found {type(mutant)} instead")

                mandatory_keys = {Constants.FILE_TO_MUTATE, Constants.MUTANTS_LOCATION}
                mutant_keys = set(mutant.keys())

                missing_keys = mandatory_keys - mutant_keys
                extra_keys = mutant_keys - mandatory_keys

                if missing_keys:
                    raise Util.CertoraUserInputError(f"manual_mutants object must contain keys: {mandatory_keys}, "
                                                     f"missing: {missing_keys}")
                if extra_keys:
                    raise Util.CertoraUserInputError(f"manual_mutants object contains invalid keys: {extra_keys}, "
                                                     f"only allowed: {mandatory_keys}")
                try:
                    Vf.validate_readable_file(mutant[Constants.FILE_TO_MUTATE], Util.SOL_EXT)
                except Exception as e:
                    raise Util.CertoraUserInputError(f"Invalid file_to_mutate in manual mutant: {mutant[Constants.FILE_TO_MUTATE]}", e)

                mutants_location = mutant[Constants.MUTANTS_LOCATION]
                if Path(mutants_location).is_dir():
                    try:
                        Vf.validate_dir(mutants_location)
                    except Exception as e:
                        raise Util.CertoraUserInputError(f"Invalid directory for mutants location {mutants_location}",
                                                         e)
                else:
                    try:
                        Vf.validate_readable_file(mutants_location, Util.SOL_EXT)
                    except Exception as e:
                        raise Util.CertoraUserInputError(f"Invalid file for mutants location {mutants_location}", e)

    def mutation_attribute_in_prover(self) -> None:
        gambit_attrs = ['filename', 'contract', 'functions', 'seed', 'num_mutants']
        mutation_attrs = [attr.get_conf_key() for attr in MutAttrs.MutateAttributes.attribute_list()]
        prover_attrs = [attr.get_conf_key() for attr in Attrs.EvmProverAttributes.attribute_list()]

        for key in vars(self.mutate_app.prover_context):
            if key not in prover_attrs:
                if key in mutation_attrs:
                    raise Util.CertoraUserInputError(f"{key} is a legal mutation key but illegal prover attribute, "
                                                     "should it be under the mutation section? ")
                if key in gambit_attrs:
                    raise Util.CertoraUserInputError(f"{key} is a legal gambit key but illegal prover attribute, "
                                                     "should it be under a gambit section? ")

    def validate_attribute_combinations(self) -> None:
        if not self.mutate_app.gambit and self.mutate_app.outdir != Constants.GAMBIT_OUT:
            raise Util.CertoraUserInputError("Invalid configuration: 'outdir' should not be set "
                                             "when 'gambit' mutations are not configured.")
        if not self.mutate_app.gambit and not self.mutate_app.manual_mutants and not self.mutate_app.universal_mutator:
            raise Util.CertoraUserInputError("at least one gambit object, manual mutant or universal mutator"
                                             "must exist in the config file")

    def validate_gambit_objs(self) -> None:
        valid_gambit_attributes = [
            'contract',
            'filename',
            'functions',
            'mutations',
            'num_mutants',
            'seed'
        ]
        if self.mutate_app.gambit:
            for el in self.mutate_app.gambit:
                if 'filename' not in el:
                    raise Util.CertoraUserInputError(f"mandatory 'filename' not in gambit: \n\n\t\t{el}")
                try:
                    Vf.validate_readable_file(el['filename'], Util.SOL_EXT)
                except Exception as e:
                    raise Util.CertoraUserInputError(f"Invalid gambit object {el}", e)

                for key in el.keys():
                    lkey = key.lower()
                    if lkey.startswith(Constants.SOLC):
                        raise Util.CertoraUserInputError("flags to Solidity should be set from the original run not "
                                                         f"inside the gambit entry in the conf file ({lkey})")
                    if lkey in [str(Constants.OUTDIR), Constants.SKIP_VALIDATE, Constants.GAMBIT_NO_OVERWRITE]:
                        raise Util.CertoraUserInputError(f"{lkey} not allowed inside embedded gambit object when "
                                                         f"running certoraMutate")
                    if lkey not in valid_gambit_attributes:
                        raise Util.CertoraUserInputError(f"{lkey} is not a valid attribute for a gambit object")

    def validate_arg_types(self) -> None:

        for arg in MutAttrs.MutateAttributes.attribute_list():
            attr = getattr(self.mutate_app, arg.get_conf_key(), None)
            if attr is None or (attr is False and AttrUtil.AttrArgType.BOOLEAN):
                continue

            if arg.arg_type == AttrUtil.AttrArgType.STRING:
                self.validate_type_string(arg)
            elif arg.arg_type == AttrUtil.AttrArgType.BOOLEAN:
                self.validate_type_boolean(arg)
            elif arg.arg_type == AttrUtil.AttrArgType.INT:
                self.validate_type_int(arg)
            elif arg.arg_type == AttrUtil.AttrArgType.MAP:
                self.validate_type_any(arg)
            elif arg.arg_type == AttrUtil.AttrArgType.OBJ_LIST:
                pass  # currently used only in certoraMutate, are not validated here
            else:
                raise RuntimeError(f"{attr.arg_type} - unknown arg type")

    def validate_type_string(self, attr: AttrUtil.AttributeDefinition) -> None:
        key, value = self.__get_key_and_value(attr)

        if value is None:
            raise RuntimeError(f"calling validate_type_string with null value {key}")
        if not isinstance(value, (str, Path, int)):
            raise Util.CertoraUserInputError(f"value of {key} {value} is not a string, integer, or Path")
        attr.validate_value(str(value))

    def validate_type_any(self, attr: AttrUtil.AttributeDefinition) -> None:
        key, value = self.__get_key_and_value(attr)

        if value is None:
            raise RuntimeError(f"calling validate_type_any with null value {key}")
        attr.validate_value(value)

    def validate_type_int(self, attr: AttrUtil.AttributeDefinition) -> None:
        key, value = self.__get_key_and_value(attr)

        if value is None:
            raise RuntimeError(f"calling validate_type_string with null value {key}")
        if not isinstance(value, int):
            raise Util.CertoraUserInputError(f"value of {key} {value} is not an integer")
        attr.validate_value(str(value))

    def validate_type_boolean(self, attr: AttrUtil.AttributeDefinition) -> None:
        key, value = self.__get_key_and_value(attr)

        if value is None:
            raise RuntimeError(f"{key}: calling validate_type_boolean with None")
        elif type(value) is list and len(value) == 0:
            setattr(self.mutate_app, key, True)
        elif value not in [True, False]:
            raise Util.CertoraUserInputError(f"value of {key} {value} is not a boolean (true/false)")

    def __get_key_and_value(self, attr: AttrUtil.AttributeDefinition) -> Tuple[str, Any]:
        key = attr.get_conf_key()
        value = getattr(self.mutate_app, key, None)
        return key, value
