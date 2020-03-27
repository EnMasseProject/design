/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

import React, { useState } from "react";
import {
  SelectOption,
  SelectOptionObject,
  DataToolbarChipGroup,
  DataToolbarChip
} from "@patternfly/react-core";
import { RETURN_ALL_ADDRESS_SPACES_FOR_NAME_OR_NAMESPACE } from "graphql-module/queries";
import { ISearchNameOrNameSpaceAddressSpaceListResponse } from "schema/ResponseTypes";
import { useApolloClient } from "@apollo/react-hooks";
import { FetchPolicy, TYPEAHEAD_REQUIRED_LENGTH } from "constant";
import { ISelectOption, getSelectOptionList } from "utils";
import { AddressSpaceFilter } from "modules/address-space/components";

export interface IAddressSpaceFilterContainerProps {
  filterValue?: string;
  setFilterValue: (value: string) => void;
  filterNames: any[];
  setFilterNames: (value: Array<any>) => void;
  filterNamespaces: any[];
  setFilterNamespaces: (value: Array<any>) => void;
  filterType?: string | null;
  setFilterType: (value: string | null) => void;
  totalAddressSpaces: number;
}

export const AddressSpaceFilterContainer: React.FunctionComponent<IAddressSpaceFilterContainerProps> = ({
  filterValue,
  setFilterValue,
  filterNames,
  setFilterNames,
  filterNamespaces,
  setFilterNamespaces,
  filterType,
  setFilterType,
  totalAddressSpaces
}) => {
  const client = useApolloClient();
  const [isSelectNameExpanded, setIsSelectNameExpanded] = useState<boolean>(
    false
  );
  const [isSelectNamespaceExpanded, setIsSelectNamespaceExpanded] = useState<
    boolean
  >(false);
  const [nameSelected, setNameSelected] = useState<string>();
  const [namespaceSelected, setNamespaceSelected] = useState<string>();
  const [nameOptions, setNameOptions] = useState<Array<ISelectOption>>();
  const [nameInput, setNameInput] = useState<string>("");
  const [nameSpaceInput, setNameSpaceInput] = useState<string>("");
  const [namespaceOptions, setNamespaceOptions] = useState<
    Array<ISelectOption>
  >();

  const filterMenuItems = [
    { key: "filterName", value: "Name" },
    { key: "filterNamespace", value: "Namespace" },
    { key: "filterType", value: "Type" }
  ];
  const typeFilterMenuItems = [
    { key: "typeStandard", value: "Standard" },
    { key: "typeBrokered", value: "Brokered" }
  ];

  const onClickSearchIcon = (event: any) => {
    if (filterValue) {
      if (filterValue === "Name") {
        if (nameSelected && nameSelected.trim() !== "" && filterNames)
          if (filterNames.map(filter => filter.value).indexOf(nameSelected) < 0)
            setFilterNames([
              ...filterNames,
              { value: nameSelected.trim(), isExact: true }
            ]);
        if (!nameSelected && nameInput && nameInput.trim() !== "")
          if (
            filterNames.map(filter => filter.value).indexOf(nameInput.trim()) <
            0
          )
            setFilterNames([
              ...filterNames,
              { value: nameInput.trim(), isExact: false }
            ]);
        setNameSelected(undefined);
      } else if (filterValue === "Namespace") {
        if (namespaceSelected && namespaceSelected.trim() !== "" && filterNames)
          if (
            filterNamespaces
              .map(filter => filter.value)
              .indexOf(namespaceSelected) < 0
          ) {
            setFilterNamespaces([
              ...filterNamespaces,
              { value: namespaceSelected.trim(), isExact: true }
            ]);
          }
        if (
          !namespaceSelected &&
          nameSpaceInput &&
          nameSpaceInput.trim() !== ""
        )
          if (
            filterNamespaces
              .map(filter => filter.value)
              .indexOf(nameSpaceInput.trim()) < 0
          )
            setFilterNamespaces([
              ...filterNamespaces,
              { value: nameSpaceInput.trim(), isExact: false }
            ]);
        setNamespaceSelected(undefined);
      }
    }
  };

  const onDelete = (
    category: string | DataToolbarChipGroup,
    chip: string | DataToolbarChip
  ) => {
    let index;
    switch (category) {
      case "Name":
        if (filterNames && chip) {
          index = filterNames
            .map(filter => filter.value)
            .indexOf(chip.toString());
          if (index >= 0) filterNames.splice(index, 1);
          setFilterNames([...filterNames]);
        }
        break;
      case "Namespace":
        if (filterNamespaces && chip) {
          index = filterNamespaces
            .map(filter => filter.value)
            .indexOf(chip.toString());
          if (index >= 0) filterNamespaces.splice(index, 1);
          setFilterNamespaces([...filterNamespaces]);
        }
        setFilterNamespaces([...filterNamespaces]);
        break;
      case "Type":
        setFilterType(null);
        break;
    }
  };

  const onFilterSelect = (value: string) => {
    setFilterValue(value);
  };

  const onTypeFilterSelect = (value: string) => {
    setFilterType(value);
  };

  const onNameSelectToggle = () => {
    setIsSelectNameExpanded(!isSelectNameExpanded);
  };

  const onNamespaceSelectToggle = () => {
    setIsSelectNamespaceExpanded(!isSelectNamespaceExpanded);
  };

  const onChangeNameData = async (value: string) => {
    setNameOptions(undefined);

    if (value.trim().length < TYPEAHEAD_REQUIRED_LENGTH) {
      setNameOptions([]);
      return;
    }
    const response = await client.query<
      ISearchNameOrNameSpaceAddressSpaceListResponse
    >({
      query: RETURN_ALL_ADDRESS_SPACES_FOR_NAME_OR_NAMESPACE(
        true,
        value.trim()
      ),
      fetchPolicy: FetchPolicy.NETWORK_ONLY
    });
    if (
      response &&
      response.data &&
      response.data.addressSpaces &&
      response.data.addressSpaces.addressSpaces &&
      response.data.addressSpaces.addressSpaces.length > 0
    ) {
      let obtainedList = response.data.addressSpaces.addressSpaces.map(
        (link: any) => {
          return link.metadata.name;
        }
      );
      //get list of unique records to display in the select dropdown based on total records and 100 fetched objects
      const filteredNameOptions = getSelectOptionList(
        obtainedList,
        response.data.addressSpaces.total
      );
      if (filteredNameOptions.length > 0) setNameOptions(filteredNameOptions);
    }
  };

  const onNameSelectFilterChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setNameInput(e.target.value);
    onChangeNameData(e.target.value);
    const options: React.ReactElement[] = nameOptions
      ? nameOptions.map((option, index) => (
          <SelectOption key={index} value={option} />
        ))
      : [];
    return options;
  };

  const onChangeNamespaceData = async (value: string) => {
    setNamespaceOptions(undefined);
    setNameOptions(undefined);
    if (value.trim().length < TYPEAHEAD_REQUIRED_LENGTH) {
      setNameOptions([]);
      return;
    }
    const response = await client.query<
      ISearchNameOrNameSpaceAddressSpaceListResponse
    >({
      query: RETURN_ALL_ADDRESS_SPACES_FOR_NAME_OR_NAMESPACE(
        false,
        value.trim()
      )
    });
    if (
      response &&
      response.data &&
      response.data.addressSpaces &&
      response.data.addressSpaces.addressSpaces &&
      response.data.addressSpaces.addressSpaces.length > 0
    ) {
      let obtainedList = response.data.addressSpaces.addressSpaces.map(
        (link: any) => {
          return link.metadata.namespace;
        }
      );
      //get list of unique records to display in the select dropdown based on total records and 100 fetched objects
      const uniqueList = getSelectOptionList(
        obtainedList,
        response.data.addressSpaces.total
      );
      if (uniqueList.length > 0) setNamespaceOptions(uniqueList);
    }
  };

  const onNamespaceSelectFilterChange = (
    e: React.ChangeEvent<HTMLInputElement>
  ) => {
    setNameSpaceInput(e.target.value);
    onChangeNamespaceData(e.target.value);
    const options: React.ReactElement[] = namespaceOptions
      ? namespaceOptions.map((option, index) => (
          <SelectOption key={index} value={option} />
        ))
      : [];
    return options;
  };

  const onNameSelect = (event: any, selection: string | SelectOptionObject) => {
    setNameSelected(selection.toString());
    setIsSelectNameExpanded(false);
  };

  const onNamespaceSelect = (
    event: any,
    selection: string | SelectOptionObject
  ) => {
    setNamespaceSelected(selection.toString());
    setIsSelectNamespaceExpanded(false);
  };

  const checkIsFilterApplied = () => {
    if (
      (filterNames && filterNames.length > 0) ||
      (filterNamespaces && filterNamespaces.length > 0) ||
      (filterType && filterType.trim() !== "")
    ) {
      return true;
    }
    return false;
  };

  return (
    <AddressSpaceFilter
      onFilterSelect={onFilterSelect}
      onDelete={onDelete}
      onNameSelectToggle={onNameSelectToggle}
      onNameSelect={onNameSelect}
      setNameSelected={setNameSelected}
      setIsSelectNameExpanded={setIsSelectNameExpanded}
      onNameSelectFilterChange={onNameSelectFilterChange}
      onClickSearchIcon={onClickSearchIcon}
      onNamespaceSelectToggle={onNamespaceSelectToggle}
      onNamespaceSelect={onNamespaceSelect}
      setNamespaceSelected={setNamespaceSelected}
      setIsSelectNamespaceExpanded={setIsSelectNamespaceExpanded}
      onTypeFilterSelect={onTypeFilterSelect}
      onNamespaceSelectFilterChange={onNamespaceSelectFilterChange}
      checkIsFilterApplied={checkIsFilterApplied}
      filterValue={filterValue}
      filterNames={filterNames}
      filterNamespaces={filterNamespaces}
      filterType={filterType}
      totalAddressSpaces={totalAddressSpaces}
      namespaceSelected={namespaceSelected}
      isSelectNamespaceExpanded={isSelectNamespaceExpanded}
      namespaceOptions={namespaceOptions}
      nameSpaceInput={nameSpaceInput}
      typeFilterMenuItems={typeFilterMenuItems}
      filterMenuItems={filterMenuItems}
      isSelectNameExpanded={isSelectNameExpanded}
      nameOptions={nameOptions}
      nameInput={nameInput}
      nameSelected={nameSelected}
    />
  );
};