# P1 Optimization Implementation Plan

## Objective

Implement the P1 optimization defined in:

`docs/checklist/EVERYTHING_THAT_NEEDS_TO_BE_DONE_BEFORE_PROTOTYPE_SUB.md`

The optimization is intended to reduce unnecessary analytics-event emissions while preserving every incoming raw reading/event and maintaining complete traceability from an emitted aggregate back to its source raw records.

## Repository Context

ULPF currently receives events through the runtime ingestion flow.

The existing ingestion path already provides:

- event ID generation
- lineage ID assignment
- raw-first event handling
- raw payload preservation
- ClickHouse raw-event persistence
- buffered ingestion into `ulpf_raw.raw_events`

The optimization must therefore operate without removing or altering the lossless raw-event preservation requirement.

## P1 Requirements

The implementation must support:

1. Delta-based emission
2. Maximum-interval emission
3. Preservation of every raw reading/event
4. Lineage grouping for every emitted aggregate
5. Verification of raw-to-aggregate backtracking

## Current Emission Rule

```text
IF |current - last_emitted| >= delta
       → emit
ELSE IF time_since_last_emission >= max_interval
       → emit
ELSE
       → don't emit analytics event