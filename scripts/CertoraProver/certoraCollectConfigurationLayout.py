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
from enum import Enum
from typing import Optional, Dict, Any
from pathlib import Path
import sys

scripts_dir_path = Path(__file__).parent.resolve()  # containing directory
sys.path.insert(0, str(scripts_dir_path))

import CertoraProver.certoraContextAttributes as Attrs
from CertoraProver.certoraCollectRunMetadata import RunMetaData, MetadataEncoder
import Shared.certoraUtils as Utils


class MainSection(Enum):
    GENERAL = "GENERAL"
    SOLIDITY_COMPILER = "SOLIDITY_COMPILER"
    GIT = "GIT"
    FILES = "FILES"
    LINKS = "LINKS"
    PACKAGES = "PACKAGES"
    METADATA = "METADATA"


class FlagType(Enum):
    VALUE_FLAG = "VALUE"
    LIST_FLAG = "LIST"
    MAP_FLAG = "MAP"


DOC_LINK_PREFIX = 'https://docs.certora.com/en/latest/docs/'
GIT_ATTRIBUTES = ['origin', 'revision', 'branch', 'dirty']
SPECIAL_MAIN_SECTIONS = ['files', 'links', 'packages']


class AttributeJobConfigData:
    """
    Collect information about attribute configuration presented in the Config tab of the Rule Report.
    This should be added to the AttributeDefinition and configured for every new attribute
    presented in the Rule report.

    Note: Attributes which do not contain specific information will be assigned as a Flag in the General main section!

    arguments:
    - main_section : MainSection -- the main section inside the config tab
        default: MainSection.GENERAL
    - subsection : str -- the subsection within the main_section (e.g Flags)
        default: Flags
    - doc_link : Optional[str] -- a link to the Documentation page of this attribute (if exists)
        default: 'https://docs.certora.com/en/latest/docs/' + Solana/EVM path + #<attribute_name>
    - tooltip : Optional[str] -- a description of this attribute to present in the config tab
        default: ''
    - unsound : bool -- an indicator if this attribute is sound or potentially unsound
        default: False
    """

    def __init__(self, main_section: MainSection = MainSection.GENERAL, subsection: str = '',
                 doc_link: Optional[str] = '', tooltip: Optional[str] = '', unsound: bool = False):
        self.main_section = main_section
        self.subsection = subsection
        self.doc_link = doc_link
        self.tooltip = tooltip
        self.unsound = unsound


class RunConfigurationLayout:
    """
    Collect information about run configuration presented in the Config tab of the Rule Report.
    RunConfigData is aggregated from conf attributes, cmd arguments and metadata provided as input.

    arguments:
    configuration_layout : Dict -- An aggregated configuration for a specific run, nested by main section, subsection.
        Each leaf contains data about attribute value, type, documentation link and UI data.
    """
    def __init__(self, configuration_layout: Dict[str, Any]):
        # Dynamically allocate class attributes from dict
        for key, value in configuration_layout.items():
            setattr(self, key, value)

    def __repr__(self) -> str:
        return "\n".join(f"{key}: {value}" for key, value in self.__dict__.items())

    @classmethod
    def dump_file(cls, data: dict) -> None:
        with Utils.get_configuration_layout_data_file().open("w+") as f:
            json.dump(data, f, indent=4, sort_keys=True, cls=MetadataEncoder)

    @classmethod
    def load_file(cls) -> dict:
        try:
            with Utils.get_configuration_layout_data_file().open() as f:
                return json.load(f)
        except Exception as e:
            print(f"failed to load configuration layout file {Utils.get_configuration_layout_data_file()}\n{e}")
            raise

    def dump(self) -> None:
        if self.__dict__:  # dictionary containing all the attributes defined for GitInfo
            try:
                self.dump_file(self.__dict__)
            except Exception as e:
                print(f"failed to write configuration layout file {Utils.get_configuration_layout_data_file()}\n{e}")
                raise


def collect_configuration_layout() -> RunConfigurationLayout:
    """
    Collect information about run metadata and uses it to create RunConfigurationLayoutData object
    If loading metadata fails, collecting configuration layout will fail as well and return an empty object.
    """
    try:
        metadata = RunMetaData.load_file()
    except Exception as e:
        print(f"failed to load job metadata! cannot create a configuration layout file without metadata!\n{e}")
        return RunConfigurationLayout(configuration_layout={})

    attributes_configs = collect_attribute_configs(metadata)
    configuration_layout = collect_run_config_from_metadata(attributes_configs, metadata)

    return RunConfigurationLayout(configuration_layout=configuration_layout)


def get_doc_link(attr) -> str:  # type: ignore
    """
    Build dynamically a link to a specific attribute in Certora Documentation based on the attribute application.
    arguments:
    - attr: Attrs.AttributeDefinition -- current attribute to build a Documentation link for
    returns:
    - str -- a link to the correct attribute's Documentation link
    """

    # Once Soroban will have proper documentation we would need to adjust the suffix link.
    rust_suffix = Attrs.is_rust_app() and (attr.name in Attrs.SolanaProverAttributes.__dict__ or
                                           attr.name in Attrs.RustAttributes.__dict__)

    doc_link_suffix = 'solana/' if rust_suffix else 'prover/cli/'
    doc_link = f'{DOC_LINK_PREFIX}{doc_link_suffix}options.html#{attr.name.lower().replace("_", "-")}'

    return doc_link


def collect_attribute_configs(metadata: dict) -> dict:
    attr_list = Attrs.get_attribute_class().attribute_list()
    output: Dict[str, Any] = {}

    for attr in attr_list:
        attr_name = attr.name.lower()
        if attr.config_data is None:
            continue

        if metadata.get(attr_name) is None and metadata.get('conf', {}).get(attr_name) is None:
            continue

        attr_value = metadata.get(attr_name) or metadata.get('conf', {}).get(attr_name)
        config_data: AttributeJobConfigData = attr.config_data
        doc_link = config_data.doc_link or get_doc_link(attr)

        # Get or create the main section
        main_section_key = config_data.main_section.value.lower()
        main_section = output.setdefault(main_section_key, {})

        # Get or create the subsection (if it exists) and flag_type
        if isinstance(attr_value, list):
            # Files, Links and Packages are special cases where the main section is the attribute itself
            if main_section_key in SPECIAL_MAIN_SECTIONS:
                current_section = output
                attr_name = main_section_key
            else:
                current_section = main_section

            flag_type = FlagType.LIST_FLAG
        elif isinstance(attr_value, dict):
            current_section = main_section
            flag_type = FlagType.MAP_FLAG
        else:
            subsection_key = config_data.subsection.lower() if config_data.subsection else 'flags'
            current_section = main_section.setdefault(subsection_key, {})
            flag_type = FlagType.VALUE_FLAG

        # Update the current section with attribute details
        current_section[attr_name] = {
            'value': attr_value,
            'flag_type': flag_type.value.lower(),
            'doc_link': doc_link,
            'tooltip': config_data.tooltip,
            'unsound': config_data.unsound
        }

    return output


def collect_run_config_from_metadata(attributes_configs: dict, metadata: dict) -> dict:
    """
    Adding CLI and Git configuration from metadata
    """
    metadata_section = attributes_configs.setdefault(MainSection.METADATA.value.lower(), {})

    # Define a mapping of metadata attributes to their keys in general_section
    metadata_mappings = {
        'cli_version': metadata.get('CLI_version'),
        'main_spec': metadata.get('main_spec'),
        'solc_version': metadata.get('conf', {}).get('solc'),
        'verify': metadata.get('conf', {}).get('verify'),
    }

    # Add metadata attributes dynamically if they exist
    for key, value in metadata_mappings.items():
        if value:
            metadata_section[key] = {
                'value': value,
                'flag_type': FlagType.VALUE_FLAG.value,
                'doc_link': '',
                'tooltip': '',
            }

    # Adding GIT configuration from metadata
    git_section = attributes_configs.setdefault(MainSection.GIT.value.lower(), {})
    for attr in GIT_ATTRIBUTES:
        if attr_value := metadata.get('attr'):
            git_section[attr] = {
                'value': attr_value,
                'flag_type': FlagType.MAP_FLAG.value,
                'doc_link': '',
                'tooltip': ''
            }

    return attributes_configs
