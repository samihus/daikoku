import React, { useState, useEffect, useMemo } from 'react';
import { useTable, usePagination, useSortBy, useFilters } from 'react-table';
import classNames from 'classnames';
import _ from 'lodash';
import PropTypes from 'prop-types';
import Pagination from 'react-paginate';
import Select from 'react-select';

import { t, Translation } from '../../locales';
import { Spinner } from '../utils';
import { DefaultColumnFilter } from '.';

export function useForceUpdate() {
  const [, setTick] = useState(0);
  const update = React.useCallback(() => {
    setTick((tick) => tick + 1);
  }, []);
  return update;
}

export const Table = ({
  fetchItems,
  columns,
  injectTopBar,
  injectTable,
  defaultSort,
  defaultSortDesc,
  search,
  pageSizee = 15,
  mobileSize = 767,
  currentLanguage,
}) => {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(undefined);
  const forceUpdate = useForceUpdate();

  const filterTypes = React.useMemo(
    () => ({
      // "startWith"
      text: (rows, id, filterValue) => {
        return rows.filter((row) => {
          const rowValue = row.values[id];
          return rowValue !== undefined
            ? String(rowValue).toLowerCase().startsWith(String(filterValue).toLowerCase())
            : true;
        });
      },
    }),
    []
  );
  const EditableCell = ({
    value: initialValue,
    row: { index },
    column: { id },
    updateMyData, // This is a custom function that we supplied to our table instance
    editable,
  }) => {
    // We need to keep and update the state of the cell normally
    const [value, setValue] = React.useState(initialValue);

    const onChange = (e) => {
      setValue(e.target.value);
    };

    // We'll only update the external data when the input is blurred
    const onBlur = () => {
      updateMyData(index, id, value);
    };

    // If the initialValue is changed externall, sync it up with our state
    React.useEffect(() => {
      setValue(initialValue);
    }, [initialValue]);

    if (!editable) {
      return `${initialValue}`;
    }

    return <input value={value} onChange={onChange} onBlur={onBlur} />;
  };
  const defaultColumn = React.useMemo(
    () => ({
      // Let's set up our default Filter UI
      Filter: DefaultColumnFilter,
      // And also our default editable cell
      Cell: EditableCell,
    }),
    []
  );

  const {
    getTableProps,
    getTableBodyProps,
    headerGroups,
    rows,
    prepareRow,
    page,
    pageOptions,
    gotoPage,
    setPageSize,
    state: { pageSize },
  } = useTable(
    {
      columns,
      data: items,
      defaultColumn,
      filterTypes,
      initialState: {
        pageSize: 10,
        pageIndex: 0,
        sortBy: useMemo(
          () => [
            {
              id: defaultSort || columns[0].title,
              desc: defaultSortDesc || false,
            },
          ],
          []
        ),
      },
    },
    useFilters,
    useSortBy,
    usePagination
  );

  useEffect(() => {
    const sizeListener = _.debounce(() => {
      forceUpdate();
    }, 400);
    window.addEventListener('resize', sizeListener);

    if (injectTable) {
      injectTable({ update: () => update() });
    }

    update();

    return () => {
      window.removeEventListener('resize', sizeListener);
    };
  }, []);

  useEffect(() => {
    if (error) {
      setLoading(false);
    }
  }, [error]);

  useEffect(() => {
    setLoading(false);
    setError(undefined);
  }, [items]);

  const update = () => {
    setLoading(true);
    return fetchItems().then(
      (rawItems) => {
        if (rawItems.error) {
          setError(rawItems);
        } else {
          setItems(rawItems);
          setLoading(false);
        }
      },
      (e) => setError(e)
    );
  };

  if (error) {
    return <h3>{`Something went wrong: ${error.error}`}</h3>;
  }

  if (loading) {
    return <Spinner />;
  }

  const customStyles = {
    control: (base) => ({
      ...base,
      height: 30,
      minHeight: 30,
    }),
  };

  const tablePagination = (
    <div className="d-flex flex-row align-items-baseline justify-content-end flex-grow-1">
      <span>
        {rows.length}{' '}
        <Translation i18nkey="Result" language={currentLanguage} isPlural={rows.length > 1}>
          Results
        </Translation>
      </span>
      <Select
        className="reactSelect reactSelect-pagination col-3 ml-3 mr-3"
        value={{
          label: t('Show.results', currentLanguage, false, `Show ${pageSize}`, pageSize),
          value: pageSize,
        }}
        options={[10, 20, 50, 100].map((x) => ({ label: `Show ${x}`, value: x }))}
        onChange={(e) => setPageSize(Number(e.value))}
        classNamePrefix="reactSelect"
        styles={customStyles}
      />
      <Pagination
        containerClassName="pagination"
        previousLabel={t('<', currentLanguage)}
        nextLabel={t('>', currentLanguage)}
        breakLabel={'...'}
        breakClassName={'break'}
        pageCount={pageOptions.length}
        marginPagesDisplayed={1}
        pageRangeDisplayed={5}
        onPageChange={({ selected }) => gotoPage(selected)}
        pageClassName={'page-selector'}
        activeClassName={'active'}
      />
      <button
        type="button"
        className="ml-3 btn btn-sm btn-access-negative float-right"
        title={t('Reload the table content', currentLanguage)}
        onClick={update}>
        <span className="fas fa-sync-alt" />
      </button>
    </div>
  );

  return (
    <div>
      <div>
        <div className="rrow section">
          <div className="row" style={{ marginBottom: 10 }}>
            <div className="col-md-12 d-flex">
              {injectTopBar && <div style={{ fontSize: 14 }}>{injectTopBar()}</div>}
              {tablePagination}
            </div>
          </div>
          <table {...getTableProps()} className="reactTableV7">
            <thead>
              {headerGroups.map((headerGroup, idx) => (
                <tr key={`thead-tr-${idx}`} {...headerGroup.getHeaderGroupProps()}>
                  {headerGroup.headers.map((column, idx) => (
                    <th
                      key={`thead-th-${idx}`}
                      className={classNames({
                        '--sort-asc': column.isSorted && !column.isSortedDesc,
                        '--sort-desc': column.isSorted && column.isSortedDesc,
                      })}
                      style={column.style}>
                      <div {...column.getHeaderProps(column.getSortByToggleProps())}>
                        {column.render('Header')}
                      </div>
                      <div className="my-2">
                        {column.canFilter ? column.render('Filter') : null}
                      </div>
                    </th>
                  ))}
                </tr>
              ))}
            </thead>
            <tbody {...getTableBodyProps()}>
              {page.map((row, idx) => {
                prepareRow(row);
                return (
                  <tr {...row.getRowProps()} key={`tr-${idx}`}>
                    {row.cells.map((cell, idx) => {
                      return (
                        <td style={cell.column.style} {...cell.getCellProps()} key={`td-${idx}`}>
                          {cell.render('Cell')}
                        </td>
                      );
                    })}
                  </tr>
                );
              })}
            </tbody>
          </table>
          {tablePagination}
        </div>
      </div>
    </div>
  );
};

Table.propTypes = {
  columns: PropTypes.array.isRequired,
  fetchItems: PropTypes.func.isRequired,
};
