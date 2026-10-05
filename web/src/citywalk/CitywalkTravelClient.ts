import { HappyOysterEngine, isSdkError } from '@happy-oyster/js-sdk'
import type { AdventureCommand, Travel, TravelStatus } from '@happy-oyster/js-sdk'

export type { AdventureCommand, TravelStatus }

export interface CitywalkTravelRequest {
  apiHost: string
  token: string
  ticket: string
  model: string
  videoElement: HTMLVideoElement
  maxExperienceTimeSec: 60 | 90 | 120
}

/** Browser media adapter for the SDK's Citywalk capability. */
export class CitywalkTravelClient {
  private readonly travel: Travel

  private constructor(travel: Travel) {
    this.travel = travel
  }

  static create(request: CitywalkTravelRequest): CitywalkTravelClient {
    const engine = new HappyOysterEngine({
      APIHost: request.apiHost,
      token: request.token,
      logLevel: 'warn',
    })
    alignTravelAppPath(engine, request.model)
    return new CitywalkTravelClient(engine.createTravel({
      ticket: request.ticket,
      videoElement: request.videoElement,
      maxExperienceTimeSec: request.maxExperienceTimeSec,
    }))
  }

  onStatusChanged(listener: (status: TravelStatus) => void): void {
    this.travel.on('statusChanged', listener)
  }

  onError(listener: (error: unknown) => void): void {
    this.travel.onError(listener)
  }

  start(): ReturnType<Travel['start']> { return this.travel.start() }
  end(): ReturnType<Travel['end']> { return this.travel.end() }
  can(capability: Parameters<Travel['can']>[0]): boolean { return this.travel.can(capability) }
  sendCommand(command: AdventureCommand): ReturnType<Travel['sendCommand']> {
    return this.travel.sendCommand(command)
  }
  sendInstruct(request: Parameters<Travel['sendInstruct']>[0]): ReturnType<Travel['sendInstruct']> {
    return this.travel.sendInstruct(request)
  }
  rewind(request: Parameters<Travel['rewind']>[0]): ReturnType<Travel['rewind']> {
    return this.travel.rewind(request)
  }
  pause(): ReturnType<Travel['pause']> { return this.travel.pause() }
  resume(): ReturnType<Travel['resume']> { return this.travel.resume() }
}

export function citywalkTravelErrorMessage(error: unknown): string {
  return isSdkError(error)
    ? `[${error.code}] ${error.message}`
    : error instanceof Error ? error.message : String(error)
}

/** The vendor SDK hardcodes the model family; the ticket uses a mode-specific app. */
function alignTravelAppPath(engine: HappyOysterEngine, model: string): void {
  const modelessSuffix = '/api/v2/apps/happyoyster-1.0'
  const service = (engine as unknown as { backendService?: { apiBaseUrl?: string | null } }).backendService
  const url = service?.apiBaseUrl
  if (!service || typeof url !== 'string' || !url.endsWith(modelessSuffix)) return
  service.apiBaseUrl = url.slice(0, -modelessSuffix.length) + '/api/v2/apps/' + model
}
